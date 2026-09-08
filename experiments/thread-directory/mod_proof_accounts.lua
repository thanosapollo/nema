-- Disposable fixture ONLY. Read generated credentials, never log them.
local um = require "prosody.core.usermanager";
local json = require "prosody.util.json";
module:hook_global("server-started", function ()
	local file = assert(io.open("/fixture/accounts.json"));
	local accounts = assert(json.decode(file:read("*a"))); file:close();
	for name, password in pairs(accounts) do
		if not um.user_exists(name, module.host) then
			assert(um.create_user(name, password, module.host));
		end
	end
end);
