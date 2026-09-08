-- Experimental single-authority shared directories. No message interception.
local st = require "prosody.util.stanza";
local jid = require "prosody.util.jid";
local sha256 = require "prosody.util.hashes".sha256;
local usermanager = require "prosody.core.usermanager";
local modulemanager = require "prosody.core.modulemanager";
local NS = "urn:nema:threads:0";
local store = module:open_store("thread_directory", "keyval");
local muc_host = module:get_option_string("thread_directory_muc_host");
local PAGE, CAPACITY = 3, 128;
module:depends("disco");
module:add_feature(NS);

local function uuid(value)
	return type(value) == "string" and #value == 36
		and value:match("^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$")
		and value == value:lower();
end

local function bare(value, host)
	if type(value) ~= "string" or jid.prep(value) ~= value then return false; end
	local node, domain, resource = jid.split(value);
	return node and domain == host and not resource;
end

local function title_valid(value)
	if type(value) ~= "string" or #value > 512 then return false; end
	local length = utf8.len(value);
	if not length or length > 128 then return false; end
	local nonspace = false;
	for _, c in utf8.codes(value) do
		if c < 32 or (c >= 127 and c <= 159) or c == 0x2028 or c == 0x2029 then return false; end
		local space = c == 32 or c == 0xa0 or c == 0x1680 or (c >= 0x2000 and c <= 0x200a)
			or c == 0x202f or c == 0x205f or c == 0x3000;
		if not space then nonspace = true; end
	end
	return nonspace and not value:match("^ ") and not value:match(" $");
end

-- Native room salt is durable in room._data and changes on recreation.
-- Never expose the salt or real MUC creator JID to other participants.
local function scope(q, actor)
	if q.kind == "direct" then
		if q.room or not bare(q.a, module.host) or not bare(q.b, module.host) or q.a == q.b then
			return nil, "bad-request";
		end
		if actor ~= q.a and actor ~= q.b then return nil, "forbidden"; end
		for _, peer in ipairs({ q.a, q.b }) do
			local node, host = jid.split(peer);
			if not usermanager.user_exists(node, host) then return nil, "forbidden"; end
		end
		local a, b = q.a, q.b;
		if a > b then a, b = b, a; end
		return sha256("direct\0" .. a .. "\0" .. b, true);
	elseif q.kind == "muc" then
		if q.a or q.b or not muc_host or not bare(q.room, muc_host) then return nil, "bad-request"; end
		local muc = modulemanager.get_module(muc_host, "muc");
		if not muc then return nil, "service-unavailable"; end
		local room = muc.get_room_from_jid(q.room);
		if not room or room._data.destroyed then return nil, "forbidden"; end
		local affiliation = room:get_affiliation(actor);
		if affiliation ~= "owner" and affiliation ~= "admin" and affiliation ~= "member" then
			return nil, "forbidden";
		end
		if not room:get_members_only() or not room:get_persistent() then return nil, "not-allowed"; end
		local salt = room:get_salt();
		if not room:save(true) then return nil, "internal-server-error"; end
		return sha256("muc\0" .. q.room .. "\0" .. salt, true);
	end
	return nil, "bad-request";
end

local function entry_xml(reply, entry, actor)
	reply:tag("thread", {
		id = entry.id, title = entry.title, revision = tostring(entry.revision),
		archived = tostring(entry.archived),
		can_modify = tostring(entry.creator == actor),
	}):up();
end

local allowed = {
	list = { kind=true, a=true, b=true, room=true, op=true, after=true, snapshot=true },
	create = { kind=true, a=true, b=true, room=true, op=true, id=true, title=true, operation=true, incarnation=true },
	rename = { kind=true, a=true, b=true, room=true, op=true, id=true, title=true, operation=true, expected=true, incarnation=true },
	archive = { kind=true, a=true, b=true, room=true, op=true, id=true, archived=true, operation=true, expected=true, incarnation=true },
};

local function handle(event)
	local origin, iq = event.origin, event.stanza;
	local function fail(condition)
		origin.send(st.error_reply(iq, condition == "internal-server-error" and "wait" or "cancel", condition));
		return true;
	end
	if origin.type ~= "c2s" or not origin.username or origin.host ~= module.host
		or iq.attr.from ~= origin.full_jid then return fail("not-authorized"); end
	local actor = jid.bare(origin.full_jid);
	local payload = iq:get_child("directory", NS);
	local q = payload.attr;
	if not allowed[q.op] or #iq.tags ~= 1 or #payload.tags > 0 or #payload > 0 then return fail("bad-request"); end
	for name in pairs(q) do
		if name ~= "xmlns" and not allowed[q.op][name] then return fail("bad-request"); end
	end
	if (q.op == "list" and iq.attr.type ~= "get") or (q.op ~= "list" and iq.attr.type ~= "set") then
		return fail("bad-request");
	end
	local key, scope_error = scope(q, actor);
	if not key then return fail(scope_error); end
	if q.op ~= "list" and q.kind == "muc" and q.incarnation ~= key then return fail("conflict"); end
	if q.kind == "direct" and q.incarnation then return fail("bad-request"); end
	local directory, err = store:get(key);
	if err then return fail("internal-server-error"); end
	directory = directory or { revision = 0, entries = {} };
	local ids = {};
	for id in pairs(directory.entries) do ids[#ids+1] = id; end
	table.sort(ids);
	local snapshot = key .. ":" .. tostring(directory.revision);
	local reply = st.stanza("directory", { xmlns=NS, kind=q.kind, a=q.a, b=q.b, room=q.room,
		snapshot=snapshot, incarnation=q.kind == "muc" and key or nil, total=tostring(#ids) });

	if q.op == "list" then
		if q.snapshot and q.snapshot ~= snapshot then return fail("conflict"); end
		local start = 1;
		if q.after then
			if not q.snapshot or not uuid(q.after) then return fail("bad-request"); end
			local found;
			for i, id in ipairs(ids) do if id == q.after then start=i+1; found=true; break; end end
			if not found then return fail("item-not-found"); end
		end
		local last = math.min(start + PAGE - 1, #ids);
		for i = start, last do entry_xml(reply, directory.entries[ids[i]], actor); end
		reply.attr.complete = tostring(last == #ids);
		if last < #ids then reply.attr.after = ids[last]; end
		origin.send(st.reply(iq):add_child(reply)); return true;
	end

	if not uuid(q.id) or not uuid(q.operation) then return fail("bad-request"); end
	if (q.op == "create" or q.op == "rename") and not title_valid(q.title) then return fail("bad-request"); end
	if q.op == "archive" and q.archived ~= "true" and q.archived ~= "false" then return fail("bad-request"); end
	local entry = directory.entries[q.id];
	local fingerprint = table.concat({ q.op, q.expected or "", q.title or "", q.archived or "" }, "\0");
	local replay = false;
	if entry then
		if entry.creator ~= actor then return fail("forbidden"); end
		if q.operation == entry.operation then
			if entry.fingerprint ~= fingerprint then return fail("conflict"); end
			replay = true;
		end
	end
	if not replay then
		if q.op == "create" then
			if entry then return fail("conflict"); end
			if #ids >= CAPACITY then return fail("resource-constraint"); end
			entry = { id=q.id, title=q.title, creator=actor, revision=1, archived=false };
			directory.entries[q.id] = entry;
		else
			if not entry then return fail("item-not-found"); end
			if q.expected ~= tostring(entry.revision) then return fail("conflict"); end
			if q.op == "rename" then entry.title = q.title; else entry.archived = q.archived == "true"; end
			entry.revision = entry.revision + 1;
		end
		entry.operation, entry.fingerprint = q.operation, fingerprint;
		directory.revision = directory.revision + 1;
		-- Synchronous keyval read/modify/atomic-write, no coroutine yield or cache.
		-- One Prosody process is the only writer to this store.
		if not store:set(key, directory) then return fail("internal-server-error"); end
	end
	reply.attr.snapshot = key .. ":" .. tostring(directory.revision);
	reply.attr.total = tostring(#ids + ((q.op == "create" and not replay) and 1 or 0));
	reply.attr.operation, reply.attr.replayed = q.operation, tostring(replay);
	entry_xml(reply, entry, actor);
	origin.send(st.reply(iq):add_child(reply)); return true;
end

module:hook("iq-get/host/" .. NS .. ":directory", handle);
module:hook("iq-set/host/" .. NS .. ":directory", handle);
