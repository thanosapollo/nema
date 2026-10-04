"""Source-only inventory of the Room/SQLite mechanisms owning protected rows.

SQLite authorizer inspection compiles statements; it does not run application SQL.
Native tests separately exercise the generated Room writers and transaction rollback.
"""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main/java/org/thanosapollo/nema"
SQL_LITERAL = r'(?:"""(.*?)"""|"([^"\n]*)")'


def schema():
    db = sqlite3.connect(":memory:")
    export = json.loads((ROOT / "app/schemas/org.thanosapollo.nema.storage.NemaDatabase/30.json").read_text())["database"]
    for entity in export["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for view in export.get("views", []):
        db.execute(view["createSql"].replace("${VIEW_NAME}", view["viewName"]))
    migration = (MAIN / "storage/MessageSchema.kt").read_text().split("val MIGRATION_30_31", 1)[1].split("val MIGRATION_27_28", 1)[0]
    for statement in re.findall(r'db.execSQL\("(.*?)"\)', migration):
        db.execute(statement)
    return db


def effects(db, sql):
    writes = set()
    def authorize(action, table, column, database, trigger):
        if table == "messages" and action in (sqlite3.SQLITE_UPDATE, sqlite3.SQLITE_DELETE):
            writes.add((action, column))
        return sqlite3.SQLITE_OK
    db.set_authorizer(authorize)
    try:
        db.execute("EXPLAIN " + sql, {p: None for p in re.findall(r":(\w+)", sql)})
    finally:
        db.set_authorizer(None)
    return writes


def inventory(sources):
    db = schema()
    result = []
    protected = {"protectedState", "protectedEvidence"}
    try:
        for path, source in sources.items():
            for query in re.finditer(r"@Query\(\s*" + SQL_LITERAL + r"\s*,?\s*\).*?fun\s+(\w+)\(", source, re.S):
                sql, owner = query[1] or query[2], query[3]
                changed = effects(db, sql)
                if changed:
                    result.append((path, owner, changed))
                if any(column in protected for action, column in changed):
                    if owner != "updateProtectedContent" or path != "storage/MessageCore.kt":
                        raise AssertionError(f"unowned protected tuple writer: {path}:{owner}")
                    prove_updater_scope(sql)
                if any(action == sqlite3.SQLITE_DELETE for action, _ in changed):
                    if not (path == "storage/NemaDatabase.kt" and owner == "deleteMessages"):
                        raise AssertionError(f"row deletion bypasses merge guard: {path}:{owner}")
            for update in re.finditer(r"@(Update|Upsert|Insert)(?:\((.*?)\))?\s+(?:protected\s+|abstract\s+|suspend\s+)*fun\s+(\w+)\(\w+:\s*([\w<>?]+)\)", source, re.S):
                mechanism, annotation, owner, parameter = update.groups()
                if mechanism == "Insert" and "REPLACE" not in (annotation or ""):
                    continue
                if "MessageEntity" not in (annotation or "") and "MessageEntity" not in parameter:
                    continue
                dto_name = "MessageEntity" if "MessageEntity" in parameter else parameter
                dto = next((match for text in sources.values()
                            if (match := re.search(r"data class " + dto_name + r"\((.*?)\n\)", text, re.S))), None)
                if dto is None:
                    raise AssertionError("unresolved message update shape")
                fields = re.findall(r"val\s+(\w+)\s*:", dto[1])
                sql = "UPDATE messages SET " + ",".join(f'"{field}" = :value' for field in fields)
                if any(column in protected for action, column in effects(db, sql)):
                    raise AssertionError(f"broad metadata writes protected tuple: {owner}")
            for deletion in re.finditer(r"@Delete\s+(?:protected\s+|abstract\s+|suspend\s+)*fun\s+(\w+)\(\w+:\s*([\w<>?]+)\)", source):
                if "MessageEntity" in deletion[2] and (path != "storage/MessageCore.kt" or deletion[1] != "deleteMessage"):
                    raise AssertionError("new Room message deletion entrance")
            if path != "storage/MessageCore.kt" and ".deleteMessage(" in source:
                raise AssertionError("message deletion outside guarded merge owner")
            if path != "storage/MessageSchema.kt":
                for raw in re.finditer(r"execSQL\(\s*" + SQL_LITERAL, source, re.S):
                    if any(action == sqlite3.SQLITE_DELETE or column in protected
                           for action, column in effects(db, raw[1] or raw[2])):
                        raise AssertionError(f"raw protected writer: {path}")
        core = sources["storage/MessageCore.kt"]
        merge = core.split("private suspend fun mergePair(", 1)[1].split("private suspend fun reparentArchivePositions", 1)[0]
        guard = merge.index("preflightProtectedMerge(first, second)")
        for operation in ("muc.beforeMerge(", "dao.reparentAliases(", "dao.deleteMessage("):
            if merge.index(operation) <= guard:
                raise AssertionError("merge mutation precedes protected preflight")
        outside_merge = core.replace(merge, "")
        if ".deleteMessage(" in outside_merge:
            raise AssertionError("new deletion entrance outside guarded merge")
        return result
    finally:
        db.close()


def prove_updater_scope(sql):
    db = schema()
    try:
        for account in ("a", "b"):
            db.execute("INSERT INTO accounts(id,bareJid,authenticationId,serviceDomain) VALUES(?,?,?,'example.org')", (account, account + "@example.org", account))
            db.execute("INSERT INTO peers(accountId,jid) VALUES(?,'peer@example.org')", (account,))
            for index, message in enumerate(("one", "two")):
                db.execute("""INSERT INTO messages(accountId,localMessageId,peerJid,senderJid,direction,messageKind,body,
                    localSequence,markable,directSessionTransitionApplied,liveDeliveryObserved)
                    VALUES(?,?,'peer@example.org','peer@example.org','INBOUND','CHAT','body',?,0,0,0)""", (account, message, index))
        changed = db.execute(sql, dict(accountId="a", messageId="one", state="REJECTED", evidence="sentinel")).rowcount
        rows = db.execute("SELECT accountId,localMessageId FROM messages WHERE protectedEvidence IS NOT NULL").fetchall()
        if changed != 1 or rows != [("a", "one")]:
            raise AssertionError("protected updater lacks exact account/message qualification")
        if db.execute(sql, dict(accountId="absent", messageId="one", state="REJECTED", evidence="sentinel")).rowcount != 0:
            raise AssertionError("absent account changed rows")
    finally:
        db.close()


class ProtectedWriterInventory(unittest.TestCase):
    def setUp(self):
        self.sources = {str(p.relative_to(MAIN)): p.read_text() for p in MAIN.rglob("*.kt")}

    def test_inventory(self):
        self.assertTrue(inventory(self.sources))

    def test_renamed_rogue_query_and_raw_sql_are_detected_but_reads_are_not(self):
        core = self.sources["storage/MessageCore.kt"]
        for sql in ("UPDATE messages SET protectedEvidence = NULL", "DELETE FROM messages WHERE localMessageId = :id"):
            with self.subTest(sql=sql):
                rogue = dict(self.sources)
                rogue["storage/MessageCore.kt"] = core + f'\n@Dao abstract class Rogue {{ @Query("{sql}") abstract suspend fun innocuousName(id: String) }}\n'
                with self.assertRaises(AssertionError):
                    inventory(rogue)
        harmless = dict(self.sources)
        harmless["storage/MessageCore.kt"] += '\n@Dao abstract class ReadOnly { @Query("SELECT protectedEvidence FROM messages") abstract suspend fun inspect(): List<String?> }\n'
        inventory(harmless)
        raw = dict(self.sources)
        raw["Other.kt"] = 'db.execSQL("UPDATE messages SET protectedState = \'NONE\'")'
        with self.assertRaises(AssertionError):
            inventory(raw)

    def test_full_entity_update_upsert_and_replace_are_detected(self):
        for annotation in ("@Update", "@Upsert", "@Insert(onConflict = OnConflictStrategy.REPLACE)", "@Delete"):
            rogue = dict(self.sources)
            rogue["Rogue.kt"] = f"@Dao abstract class Rogue {{ {annotation} abstract suspend fun save(row: MessageEntity) }}"
            with self.assertRaises(AssertionError):
                inventory(rogue)

    def test_scope_metadata_and_late_merge_guard_mutants_are_detected(self):
        core = self.sources["storage/MessageCore.kt"]
        mutations = [
            core.replace("WHERE accountId = :accountId AND localMessageId = :messageId\")", "WHERE localMessageId = :messageId\")", 1),
            core.replace("data class MessageMetadata(", "data class MessageMetadata(\n    val protectedEvidence: String?,", 1),
            core.replace("val protection = preflightProtectedMerge(first, second)", "val protection = null", 1)
                .replace("dao.deleteMessage(loser)", "dao.deleteMessage(loser)\n        preflightProtectedMerge(first, second)", 1),
            core + '\nfun rogueDelete(dao: MessageDao, row: MessageEntity) { dao.deleteMessage(row) }',
        ]
        for mutant in mutations:
            self.assertNotEqual(core, mutant)
            sources = dict(self.sources, **{"storage/MessageCore.kt": mutant})
            with self.assertRaises(AssertionError):
                inventory(sources)


if __name__ == "__main__":
    unittest.main()
