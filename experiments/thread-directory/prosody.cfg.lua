-- Deliberately disposable: never use this configuration for a live host.
interfaces = { "0.0.0.0" }
c2s_ports = { 5222 }
s2s_ports = {}
http_ports = {}
https_ports = {}
modules_disabled = { "s2s" }
modules_enabled = { "tls"; "saslauth"; "disco"; "roster"; "admin_shell"; }
plugin_paths = { "/experiment" }
data_path = "/fixture/data"
pidfile = "/fixture/prosody.pid"
admin_socket = "/fixture/prosody.sock"
log = { { levels = { min = "info" }; to = "console" } }
authentication = "internal_hashed"
storage = "internal"
c2s_require_encryption = true
ssl = { key = "/fixture/key.pem"; certificate = "/fixture/cert.pem"; }
VirtualHost "proof.test"
  modules_enabled = { "thread_directory"; "proof_accounts"; }
  thread_directory_muc_host = "rooms.proof.test"
Component "rooms.proof.test" "muc"
  muc_room_default_public = false
  muc_room_default_persistent = true
  muc_room_default_members_only = true
  muc_room_default_public_jids = false
  muc_tombstones = false
  muc_vcard = false
