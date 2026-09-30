import groovy.json.JsonOutput

// Named parameters consumed by Camel SQL; no JDBC calls are made in this script.
def result = body

return [
    id: result.id,
    message_id: result.email.messageId,
    source: result.email.source,
    sender: result.email.from,
    recipient: result.email.to,
    subject: result.email.subject,
    email_body: result.email.body,
    received_at: result.email.receivedAt,
    status: result.status,
    classification: result.jev.classification,
    intent: result.jev.intent,
    sentiment: result.jev.sentiment,
    draft_reply: result.openai.draft,
    jev_request: result.jevRequest,
    openai_request: result.openaiRequest,
    jev_result: JsonOutput.toJson(result.jev),
    openai_result: JsonOutput.toJson(result.openai),
    processed_at: result.processedAt
]
