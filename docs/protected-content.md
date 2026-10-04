# Unsupported protected content

Nema does not encrypt or decrypt messages. Exact legacy OMEMO
(`eu.siacs.conversations.axolotl`) and modern OMEMO (`urn:xmpp:omemo:2`)
content is retained as unsupported, unauthenticated evidence. This does not
establish sender authenticity, trust, or interoperability.

The timeline shows a client-owned status. An outer body, when present, is shown
as plain text under **Unauthenticated fallback**. Protected content cannot open
attachments or links, prepare replies or edits, send reactions, or acknowledge
remote delivery or reading. Local viewing can settle local unread state.
Header-only session content is visible but does not create unread counts or
notifications. Other protected notifications contain only a generic status.

Schema 31 adds an inline state and versioned evidence record. Existing messages,
including old `Encrypted message` placeholders, are not reclassified or treated
as recoverable ciphertext. Unsupported input retains bounded validated-carrier
observations. Rejected input retains recognized protocol presence and a finite
reason, not truncated ciphertext. Different ciphertext, incomplete evidence,
and ordinary/protected mismatches do not authorize destructive deduplication.
Unknown or damaged evidence is kept inert; it is never reinterpreted as an
ordinary message.

The native provider bounds retained selected-subtree state. These limits do not
bound the XML lexer's allocation, the whole stanza, or transport time. Unknown
OMEMO versions are outside this representation. Ordinary sending does not gain
any encryption or negotiation capability.
