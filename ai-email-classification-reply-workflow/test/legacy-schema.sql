-- Snapshot of the database schema before outgoing request capture was added.
CREATE TABLE email_results (
    id TEXT PRIMARY KEY,
    message_id TEXT,
    source TEXT NOT NULL,
    sender TEXT NOT NULL,
    recipient TEXT NOT NULL,
    subject TEXT NOT NULL,
    email_body TEXT NOT NULL,
    received_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('succeeded', 'partial', 'failed')),
    classification TEXT,
    intent TEXT,
    sentiment TEXT,
    draft_reply TEXT,
    jev_result TEXT NOT NULL CHECK (json_valid(jev_result)),
    openai_result TEXT NOT NULL CHECK (json_valid(openai_result)),
    processed_at TEXT NOT NULL
)
