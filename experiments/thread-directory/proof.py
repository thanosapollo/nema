# /// script
# requires-python = ">=3.11"
# dependencies = ["slixmpp==1.12.0"]
# ///
"""Bounded real-XMPP proof; every directory result comes from Prosody IQs."""
from __future__ import annotations

import asyncio
from contextlib import contextmanager
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import tarfile
import time
import uuid
from xml.etree import ElementTree as ET

import slixmpp
from slixmpp.exceptions import IqError

ROOT = Path(__file__).resolve().parent
NS = "urn:nema:threads:0"
HOST = "proof.test"
ROOM = "directory@rooms.proof.test"
IMAGE = "sha256:712415a5b1156ad68929ab024ff04750420f98f60da6a47bdb088f4ddafc112a"
PAIR = {"kind": "direct", "a": "alice@proof.test", "b": "bob@proof.test"}
GROUP = {"kind": "muc", "room": ROOM}
checks: list[str] = []


def check(condition: bool, label: str) -> None:
    if not condition:
        raise AssertionError(label)
    checks.append(label)
    print(f"PASS {label}", flush=True)


def command(*args: str, timeout: int = 30) -> str:
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stderr[:2000]}")
    return result.stdout.strip()


OWNER_LABEL = "org.nema.thread-proof.owner"


class CleanupError(RuntimeError):
    """The daemon did not confirm settlement of this run's exact target."""


class OwnedContainer:
    def __init__(self):
        self.name = "nema-thread-proof-" + secrets.token_hex(8)
        self.owner = secrets.token_hex(32)  # Ownership marker, not a credential.
        self.attempted = False
        self.acknowledged = False
        self.identifier: str | None = None

    def launch(self, *args: str) -> str:
        self.attempted = True  # The daemon may create before the CLI reports failure.
        command("docker", "run", "-d", "--name", self.name,
                "--label", f"{OWNER_LABEL}={self.owner}", *args)
        self.acknowledged = True
        identifier = self.owned_id()
        if identifier is None:
            raise RuntimeError(f"Launch did not resolve to owned container {self.name}")
        return identifier

    def owned_id(self) -> str | None:
        target = f"id={self.identifier}" if self.identifier else f"name=^/{self.name}$"
        identifier = command("docker", "container", "ls", "--all", "--no-trunc",
                             "--filter", target, "--format", "{{.ID}}", timeout=10)
        if not identifier:
            return None
        if len(identifier) != 64 or any(c not in "0123456789abcdef" for c in identifier):
            raise RuntimeError("Docker returned an ambiguous container ID")
        labels = json.loads(command("docker", "inspect", "--format", "{{json .Config.Labels}}",
                                    identifier, timeout=10))
        if not isinstance(labels, dict) or labels.get(OWNER_LABEL) != self.owner:
            if self.identifier:
                raise RuntimeError("Pinned container ownership changed")
            print(f"CLEANUP foreign name collision preserved: {self.name} id={identifier}", flush=True)
            return None
        self.identifier = identifier
        return identifier

    def cleanup(self) -> None:
        if not self.attempted:
            return
        try:
            identifier = self.owned_id()
            if identifier is None and not self.acknowledged and self.identifier is None:
                # Absence/collision is only a snapshot, not completion of an in-flight create.
                raise RuntimeError("launch not acknowledged and no owned ID ever pinned")
            if identifier is not None:
                removal_error = None
                try:
                    command("docker", "rm", "-f", identifier, timeout=10)
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                    removal_error = type(error).__name__
                # A failed/timed-out rm is ambiguous too: always read back the immutable ID.
                remaining = command("docker", "container", "ls", "--all", "--no-trunc",
                                    "--filter", f"id={identifier}", "--format", "{{.ID}}", timeout=10)
                if remaining:
                    raise RuntimeError(f"Owned container remains after removal ({removal_error or 'CLI success'})")
                if removal_error:
                    print(f"CLEANUP removal CLI {removal_error}; exact ID absence verified", flush=True)
                check(True, "exact task-owned container removed")
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
            raise CleanupError(
                f"Cleanup unresolved (daemon unreachable or target not settled): "
                f"name={self.name} id={self.identifier or 'unknown'}; {type(error).__name__}: {error}"
            ) from error


@contextmanager
def server_fixture():
    fixture = Path(tempfile.mkdtemp(prefix="nema-thread-proof-"))
    server = OwnedContainer()
    try:
        yield fixture, server
    finally:
        try:
            server.cleanup()
        except CleanupError as error:
            # Do not unlink a possibly live server's bind mounts/credentials.
            print(f"{error}; private fixture preserved at {fixture}", file=sys.stderr, flush=True)
            raise
        shutil.rmtree(fixture)


def new_id() -> str:
    return str(uuid.uuid4())


class Client(slixmpp.ClientXMPP):
    def __init__(self, name: str, resource: str, fixture: Path, accounts: dict[str, str]):
        super().__init__(f"{name}@{HOST}/{resource}", accounts[name])
        self.ready = asyncio.Event()
        self.received: asyncio.Queue = asyncio.Queue()
        self.directory_pushes = []
        self.enable_direct_tls = False
        self.enable_starttls = True
        self.enable_plaintext = False
        self.ssl_context = ssl.create_default_context(cafile=str(fixture / "cert.pem"))
        self.register_plugin("xep_0030")
        self.register_plugin("xep_0045")
        self.add_event_handler("session_start", self.started)
        self.add_event_handler("message", self.message_received)
        def observe_directory_push(stanza):
            if stanza.name == "message" and any(element.tag.startswith(f"{{{NS}}}") for element in stanza.xml.iter()):
                self.directory_pushes.append(stanza)
            return stanza
        self.add_filter("in", observe_directory_push)
        for event in ("connected", "connection_failed", "failed_auth", "ssl_invalid_cert", "disconnected"):
            self.add_event_handler(event, lambda _, event=event: print(f"CLIENT {resource} {event}", flush=True))

    async def started(self, _event):
        self.send_presence()
        self.ready.set()

    def message_received(self, message):
        if message["body"]:
            self.received.put_nowait(message)

    async def login(self, port: int):
        await self.connect("127.0.0.1", port)
        await asyncio.wait_for(self.ready.wait(), 15)
        return self

    async def query(self, scope: dict, op: str = "list", **fields):
        if scope["kind"] == "muc" and op != "list" and "incarnation" not in fields:
            fields["incarnation"] = (await self.query(scope)).attrib["incarnation"]
        iq = self.Iq()
        iq["to"] = HOST
        iq["type"] = "get" if op == "list" else "set"
        iq.append(ET.Element(f"{{{NS}}}directory", scope | {"op": op} | fields))
        reply = await iq.send(timeout=8)
        payload = reply.xml.find(f"{{{NS}}}directory")
        assert payload is not None and payload.attrib["kind"] == scope["kind"]
        for key in ("a", "b", "room"):
            assert payload.get(key) == scope.get(key)
        return payload

    async def full(self, scope: dict):
        # Stage a complete snapshot before returning it. No partial replacement.
        staged, fields, snapshot, expected_total = {}, {}, None, None
        for _ in range(44):  # ceil(128 / 3), plus empty; never unbounded
            page = await self.query(scope, **fields)
            if snapshot is None:
                snapshot = page.attrib["snapshot"]
            assert page.attrib["snapshot"] == snapshot
            for item in page:
                assert item.tag == f"{{{NS}}}thread" and item.attrib["id"] not in staged
                staged[item.attrib["id"]] = dict(item.attrib)
            total = int(page.attrib["total"])
            if expected_total is None:
                expected_total = total
            assert total == expected_total
            assert 0 <= total <= 128 and len(staged) <= total
            if page.attrib["complete"] == "true":
                assert len(staged) == total and "after" not in page.attrib
                return staged
            assert page.attrib["complete"] == "false" and len(page) == 3
            assert page.attrib["after"] == page[-1].attrib["id"]
            fields = {"after": page.attrib["after"], "snapshot": snapshot}
        raise AssertionError("Directory did not terminate")

    async def create(self, scope, title, identifier=None, operation=None):
        identifier, operation = identifier or new_id(), operation or new_id()
        await self.query(scope, "create", id=identifier, operation=operation, title=title)
        return identifier, operation

    async def change(self, scope, identifier, revision, op="rename", operation=None, **fields):
        return await self.query(scope, op, id=identifier, operation=operation or new_id(),
                                expected=str(revision), **fields)

    async def xml_iq(self, target, payload, kind="set"):
        iq = self.Iq()
        iq["to"], iq["type"] = target, kind
        iq.append(payload)
        return await iq.send(timeout=8)

    async def configure_room(self, room=ROOM):
        # Own join presence is the barrier; no blind delay during room creation.
        joined = asyncio.Event()
        event = f"muc::{room}::self-presence"
        self.add_event_handler(event, lambda _: joined.set())
        joining = asyncio.create_task(self.join_room(room))
        try:
            await asyncio.wait_for(joined.wait(), 8)
            query = ET.Element("{http://jabber.org/protocol/muc#owner}query")
            ET.SubElement(query, "{jabber:x:data}x", {"type": "submit"})
            await self.xml_iq(room, query)
            await joining
        finally:
            if not joining.done():
                joining.cancel()
            await asyncio.gather(joining, return_exceptions=True)

    async def join_room(self, room=ROOM):
        # Explicit pfrom avoids Slixmpp 1.12 empty-JID truthiness waiter mismatch.
        return await self.plugin["xep_0045"].join_muc_wait(
            slixmpp.JID(room), self.boundjid.user,
            presence_options={"pfrom": self.boundjid}, timeout=8)

    async def affiliation(self, name, value, room=ROOM):
        await self.plugin["xep_0045"].set_affiliation(room, value, jid=f"{name}@{HOST}", timeout=8)

    async def body(self, target, body, thread=None, group=False):
        message = self.make_message(mto=target, mbody=body, mtype="groupchat" if group else "chat")
        if thread is not None:
            message["thread"] = thread
        message.send()

    async def expect_body(self, body, thread):
        async with asyncio.timeout(8):
            while True:
                message = await self.received.get()
                if message["body"] == body:
                    assert message["thread"] == (thread or "")
                    return message


async def denied(awaitable, condition: str, label: str):
    try:
        await awaitable
    except IqError as error:
        check(error.iq["error"]["condition"] == condition, label)
    else:
        raise AssertionError(f"Expected {condition}: {label}")


async def proof(fixture: Path, accounts: dict[str, str], container: str, port: int):
    clients = []

    async def connect(name, resource):
        client = Client(name, resource, fixture, accounts)
        clients.append(client)
        return await client.login(port)

    try:
        alice = await connect("alice", "one")
        second = await connect("alice", "two")
        bob = await connect("bob", "one")
        eve = await connect("eve", "one")
        check(all(c.ready.is_set() for c in clients), "four authenticated TLS XMPP resources")
        info = await alice.plugin["xep_0030"].get_info(jid=slixmpp.JID(HOST), timeout=8)
        features = info["disco_info"]["features"]
        check(NS in features and "urn:xmpp:mam:2" not in features,
              "directory capability advertised; MAM absent")
        check(await bob.full(PAIR) == {}, "supported empty directory distinguished from unavailable")
        await denied(bob.query(PAIR, "create", id=new_id(), operation=new_id(), title=" \u00a0 "),
                     "bad-request", "blank Unicode title rejected")
        await denied(bob.query(PAIR, "create", id="main", operation=new_id(), title="Main"),
                     "bad-request", "synthetic Main cannot become a mutable directory item")
        await denied(bob.query(PAIR, "create", id=new_id(), operation=new_id(), title="x" * 129),
                     "bad-request", "oversized title rejected")
        thread, create_op = await alice.create(PAIR, "Empty project")
        first = await bob.full(PAIR)
        check(first[thread]["title"] == "Empty project" and first[thread]["can_modify"] == "false",
              "second account discovers empty named thread without any message")
        created = await alice.query(PAIR, "create", id=thread, operation=create_op, title="Empty project")
        check(created.attrib["replayed"] == "true" and len(await bob.full(PAIR)) == 1,
              "same create operation retries without duplicate")
        await denied(bob.change(PAIR, thread, 1, title="Takeover"), "forbidden",
                     "participant cannot rename another creator's thread")
        await denied(eve.query(PAIR), "forbidden", "outsider cannot list private direct pair")
        await denied(eve.create(PAIR, "Intrusion"), "forbidden", "outsider cannot create in direct pair")
        other = {"kind": "direct", "a": "alice@proof.test", "b": "eve@proof.test"}
        await denied(alice.change(other, thread, 1, title="Cross scope"), "item-not-found",
                     "thread ID gives no cross-conversation mutation authority")
        other_id, _ = await alice.create(other, "Other conversation", identifier=thread)
        check(other_id == thread and (await alice.full(PAIR))[thread]["title"] == "Empty project",
              "same opaque ID in another pair cannot overwrite original")
        await denied(alice.query({"kind": "direct", "a": "alice@proof.test", "b": "bob@remote.test"}),
                     "bad-request", "cross-domain authority explicitly unsupported")
        rename_op = new_id()
        renamed = await second.change(PAIR, thread, 1, title="Shared rename", operation=rename_op)
        check(renamed[0].attrib["revision"] == "2" and
              (await bob.full(PAIR))[thread]["title"] == "Shared rename",
              "creator's second authenticated resource renames shared title")
        repeated = await alice.change(PAIR, thread, 1, title="Shared rename", operation=rename_op)
        check(repeated.attrib["replayed"] == "true" and repeated[0].attrib["revision"] == "2",
              "last operation replay returns unchanged revision")
        await denied(alice.change(PAIR, thread, 1, title="Different", operation=rename_op),
                     "conflict", "operation ID payload reuse rejected")
        await denied(alice.query(PAIR, "create", id=thread, operation=create_op, title="Empty project"),
                     "conflict", "old create retry after rename conflicts without reverting title")
        results = await asyncio.gather(
            alice.change(PAIR, thread, 2, title="Race A"),
            second.change(PAIR, thread, 2, title="Race B"), return_exceptions=True)
        check(sum(isinstance(r, ET.Element) for r in results) == 1 and
              sum(isinstance(r, IqError) and r.iq["error"]["condition"] == "conflict" for r in results) == 1,
              "simultaneous creator-resource CAS race has exactly one winner")
        check((await bob.full(PAIR))[thread]["revision"] == "3", "stale race cannot advance revision")
        sibling, _ = await bob.create(PAIR, "Unrelated entry")
        await second.change(PAIR, thread, 3, title="After unrelated create")
        check((await bob.full(PAIR))[sibling]["title"] == "Unrelated entry",
              "per-item CAS preserves unrelated creator's concurrent item")
        await denied(alice.change(PAIR, sibling, 1, op="archive", archived="true"), "forbidden",
                     "creator authority also enforced for archive")
        await second.change(PAIR, thread, 4, op="archive", archived="true")
        for i in range(5):
            await alice.create(PAIR, f"Empty page {i}")
        directory = await bob.full(PAIR)
        check(len(directory) == 7 and directory[thread]["archived"] == "true",
              "complete multi-page directory retains all empty and archived entries")
        page = await bob.query(PAIR)
        await alice.create(PAIR, "Between pages")
        await denied(bob.query(PAIR, after=page.attrib["after"], snapshot=page.attrib["snapshot"]),
                     "conflict", "mutation between pages rejects torn snapshot rather than truncating")
        check(len(await bob.full(PAIR)) == 8, "fresh full retrieval recovers after snapshot conflict")

        await alice.configure_room()
        await alice.affiliation("bob", "member")
        await bob.join_room()
        muc_thread, _ = await alice.create(GROUP, "Empty room project")
        muc_list = await bob.full(GROUP)
        check(muc_list[muc_thread]["title"] == "Empty room project" and
              "alice@" not in json.dumps(muc_list) and "creator" not in muc_list[muc_thread],
              "native MUC member discovers empty thread; creator stays server-private")
        await second.change(GROUP, muc_thread, 1, title="Room renamed")
        await denied(bob.change(GROUP, muc_thread, 2, title="Nick takeover"), "forbidden",
                     "MUC participant nickname does not grant creator authority")
        await denied(eve.query(GROUP), "forbidden", "MUC outsider cannot list metadata")
        room_incarnation = (await bob.query(GROUP)).attrib["incarnation"]
        await denied(eve.query(GROUP, "create", id=new_id(), operation=new_id(), title="Intrusion",
                               incarnation=room_incarnation), "forbidden", "MUC outsider cannot create metadata")
        await denied(alice.change(GROUP, thread, 1, title="Wrong conversation"), "item-not-found",
                     "direct thread ID cannot mutate room directory")
        await alice.affiliation("bob", "none")
        await denied(bob.query(GROUP), "forbidden", "revoked MUC affiliation denies next request")
        await alice.affiliation("bob", "member")
        check(muc_thread in await bob.full(GROUP), "offline/nonjoined affiliated MUC member can retrieve")
        await bob.join_room()
        await alice.body(str(bob.boundjid), "Ordinary direct body", thread)
        await bob.expect_body("Ordinary direct body", thread)
        await bob.body(str(alice.boundjid), "Direct response", thread)
        await alice.expect_body("Direct response", thread)
        await alice.body(str(bob.boundjid), "Main direct body")
        await bob.expect_body("Main direct body", None)
        await alice.body(ROOM, "Ordinary group body", muc_thread, group=True)
        await bob.expect_body("Ordinary group body", muc_thread)
        await bob.body(ROOM, "Room response", muc_thread, group=True)
        await alice.expect_body("Room response", muc_thread)
        await alice.body(ROOM, "Main room body", group=True)
        await bob.expect_body("Main room body", None)
        check(True, "native direct and MUC messages both ways preserve readable bodies and exact XEP-0201 IDs; Main has no thread")
        check(eve.received.empty() and not eve.directory_pushes, "no metadata or bodies broadcast to outsider")

        # A genuinely disconnected client misses changes, then fresh instances resync.
        await bob.disconnect(wait=1)
        await second.change(GROUP, muc_thread, 2, op="archive", archived="true")
        await second.change(PAIR, thread, 5, title="Offline rename")
        expected_direct = await alice.full(PAIR)
        expected_group = await alice.full(GROUP)
        for client in clients:
            await client.disconnect(wait=1)
        await asyncio.to_thread(command, "docker", "restart", "--timeout", "5", container)
        # Docker may reassign a randomly published host port on restart.
        restarted = json.loads(await asyncio.to_thread(
            command, "docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", container))
        binding = restarted["5222/tcp"][0]
        check(binding["HostIp"] == "127.0.0.1", "server restart remains loopback-only")
        port = int(binding["HostPort"])
        await asyncio.to_thread(wait_port, port)
        alice = await connect("alice", "fresh-after-server-restart")
        bob = await connect("bob", "fresh-after-offline")
        after_direct = await bob.full(PAIR)
        after_group = await bob.full(GROUP)
        def shared(items):
            return {key: {k: v for k, v in item.items() if k != "can_modify"} for key, item in items.items()}
        check(shared(after_direct) == shared(expected_direct) and shared(after_group) == shared(expected_group),
              "actual server restart + fresh offline client full resync retain exact direct/MUC metadata")
        check(after_direct[thread]["title"] == "Offline rename" and after_group[muc_thread]["archived"] == "true",
              "missed offline rename/archive discovered without MAM")
        await alice.change(GROUP, muc_thread, 3, op="archive", archived="false")
        check((await bob.full(GROUP))[muc_thread]["archived"] == "false", "archive is reversible after restart")

        # Destroy/recreate the same room JID under a different owner.
        old_snapshot = (await bob.query(GROUP)).attrib["snapshot"]
        destroy = ET.Element("{http://jabber.org/protocol/muc#owner}query")
        ET.SubElement(destroy, "{http://jabber.org/protocol/muc#owner}destroy")
        await alice.xml_iq(ROOM, destroy)
        await denied(bob.query(GROUP), "forbidden", "destroyed room directory inaccessible")
        await bob.configure_room()
        check(await bob.full(GROUP) == {}, "recreated same room JID has new incarnation and no old directory")
        await denied(bob.query(GROUP, snapshot=old_snapshot), "conflict",
                     "old incarnation snapshot rejected for new room")
        await denied(alice.query(GROUP), "forbidden", "former owner has no authority in recreated room")
        await denied(bob.query(GROUP, "create", id=new_id(), operation=new_id(), title="Old queued title",
                               incarnation=old_snapshot.split(":")[0]), "conflict",
                     "queued mutation pinned to old room incarnation cannot publish into new room")
        check(await bob.full(GROUP) == {}, "rejected old-incarnation mutation leaves new directory empty")
        config = ET.Element("{http://jabber.org/protocol/muc#owner}query")
        form = ET.SubElement(config, "{jabber:x:data}x", {"type": "submit"})
        for variable, value in (("FORM_TYPE", "http://jabber.org/protocol/muc#roomconfig"),
                                ("muc#roomconfig_membersonly", "0")):
            field = ET.SubElement(form, "{jabber:x:data}field", {"var": variable})
            ET.SubElement(field, "{jabber:x:data}value").text = value
        await bob.xml_iq(ROOM, config)
        await denied(bob.query(GROUP), "not-allowed", "ordinary non-members-only room explicitly unsupported")

        # Capacity deliberately exercised through authenticated IQs, never seeded storage.
        current = await bob.full(PAIR)
        for i in range(128 - len(current)):
            await bob.create(PAIR, f"Capacity {i}")
        before = await bob.full(PAIR)
        await denied(bob.create(PAIR, "Over capacity"), "resource-constraint",
                     "capacity overflow is explicit rejection, never item eviction")
        check(len(before) == 128 and await bob.full(PAIR) == before and before[thread]["archived"] == "true",
              "all 128 entries retrieved across 43 pages; overflow preserves archived and unrelated items")
        return port
    finally:
        for client in clients:
            await client.disconnect(wait=0)


def wait_port(port: int):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5) as connection:
                connection.sendall(b"<stream:stream to='proof.test' xmlns='jabber:client' "
                                   b"xmlns:stream='http://etherx.jabber.org/streams' version='1.0'>")
                response = connection.recv(8192)
                if b"http://etherx.jabber.org/streams" in response:
                    return
        except OSError:
            time.sleep(0.1)
    raise TimeoutError("Owned Prosody c2s listener did not become ready")


def verify_server_source(container: str, fixture: Path):
    source = fixture / "prosody-source.tar.gz"
    command("docker", "cp", f"{container}:/opt/prosody/source.tar.gz", str(source))
    check(hashlib.sha256(source.read_bytes()).hexdigest() ==
          "ec696f9cf562c3af4a04b07d3fb36a1cedcc4e69a392fddcfc524bc67d93050f",
          "Prosody 13.0.6 source archive matches pinned SHA-256")
    paths = {f"plugins/{path}": f"modules/{path}" for path in (
        "muc/mod_muc.lua", "muc/muc.lib.lua", "muc/occupant_id.lib.lua", "mod_storage_internal.lua")}
    paths["util/datamanager.lua"] = "util/datamanager.lua"
    with tarfile.open(source) as archive:
        for upstream, installed in paths.items():
            member = archive.extractfile(f"prosody-13.0.6/{upstream}")
            assert member is not None
            expected = hashlib.sha256(member.read()).hexdigest()
            actual = command("docker", "exec", container, "sha256sum", f"/opt/prosody/lib/prosody/{installed}").split()[0]
            assert actual == expected, installed
    check(True, "native MUC lifetime/affiliation and internal storage modules match upstream source")
    command("docker", "exec", container, "luac5.4", "-p", "/experiment/mod_thread_directory.lua")
    check(True, "directory Lua module syntax validated")


def main():
    if importlib.metadata.version("slixmpp") != "1.12.0":
        raise RuntimeError("Run with PYTHONPATH unset so the pinned Slixmpp is used")
    with server_fixture() as (fixture, server):
        (fixture / "data").mkdir()
        (fixture / "certs").mkdir()
        accounts = {name: secrets.token_urlsafe(24) for name in ("alice", "bob", "eve")}
        (fixture / "accounts.json").write_text(json.dumps(accounts))
        (fixture / "accounts.json").chmod(0o600)
        shutil.copyfile(ROOT / "prosody.cfg.lua", fixture / "prosody.cfg.lua")
        command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes", "-days", "1",
                "-subj", "/CN=proof.test", "-addext", "subjectAltName=DNS:proof.test,DNS:rooms.proof.test",
                "-keyout", str(fixture / "key.pem"), "-out", str(fixture / "cert.pem"))
        try:
            container = server.launch("--cpus=0.25", "--memory=512m",
                    "--pids-limit=64", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", f"{os.getuid()}:{os.getgid()}", "--publish", "127.0.0.1::5222",
                    "--mount", f"type=bind,src={fixture},dst=/fixture",
                    "--mount", f"type=bind,src={ROOT},dst=/experiment,readonly",
                    IMAGE, "--config", "/fixture/prosody.cfg.lua", "--foreground")
            bindings = json.loads(command("docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", container))
            binding = bindings["5222/tcp"][0]
            check(binding["HostIp"] == "127.0.0.1" and int(binding["HostPort"]) != 5222,
                  "only task-owned random nonstandard loopback c2s port published")
            port = int(binding["HostPort"])
            limits = json.loads(command("docker", "inspect", "--format", "{{json .HostConfig}}", container))
            check(limits["NanoCpus"] == 250000000 and limits["Memory"] == 536870912,
                  "server resource cap verified: 0.25 CPU, 512 MiB")
            print(f"SERVER image={IMAGE} Slixmpp={importlib.metadata.version('slixmpp')}", flush=True)
            print(f"MODULE sha256={hashlib.sha256((ROOT / 'mod_thread_directory.lua').read_bytes()).hexdigest()}", flush=True)
            wait_port(port)
            verify_server_source(container, fixture)
            port = asyncio.run(asyncio.wait_for(proof(fixture, accounts, container, port), timeout=240))
            hook = os.environ.get("NEMA_THREAD_PROOF_HOOK")
            if hook:
                if not Path(hook).is_absolute() or not os.access(hook, os.X_OK):
                    raise ValueError("NEMA_THREAD_PROOF_HOOK must be an absolute executable path")
                hook_config = fixture / "client.json"
                hook_config.write_text(json.dumps({
                    "host": HOST, "address": "127.0.0.1", "port": port, "namespace": NS,
                    "ca_certificate": str(fixture / "cert.pem"), "accounts": accounts,
                    "muc_host": "rooms.proof.test",
                }))
                hook_config.chmod(0o600)
                print(command(hook, str(hook_config), timeout=120), flush=True)
                check(True, "optional independent-client hook exited successfully")
            logs = command("docker", "logs", container)
            check("Failed to load module" not in logs and "stack traceback" not in logs,
                  "Prosody log has no module-load failure or Lua traceback")
        except BaseException:
            if server.identifier:
                # INFO logging only; fixture identities/content are generated test data.
                try:
                    print(command("docker", "logs", server.identifier, timeout=10)[-8000:], flush=True)
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                    print(f"Failure logs unavailable: {type(error).__name__}", file=sys.stderr, flush=True)
            raise
    check(not fixture.exists(), "temporary generated credentials, keys and server storage removed")
    print(f"RESULT {len(checks)} assertions passed; real authenticated XMPP, no fixtures masquerading as replies", flush=True)


if __name__ == "__main__":
    main()
