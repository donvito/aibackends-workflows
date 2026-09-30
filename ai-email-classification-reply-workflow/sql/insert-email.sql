INSERT INTO email_results (
    id, message_id, source, sender, recipient, subject, email_body, received_at,
    status, classification, intent, sentiment, draft_reply, jev_request, openai_request,
    jev_result, openai_result, processed_at
) VALUES (
    :#id, :#message_id, :#source, :#sender, :#recipient, :#subject, :#email_body, :#received_at,
    :#status, :#classification, :#intent, :#sentiment, :#draft_reply, :#jev_request, :#openai_request,
    :#jev_result, :#openai_result, :#processed_at
)
