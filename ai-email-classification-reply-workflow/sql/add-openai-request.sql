ALTER TABLE email_results ADD COLUMN openai_request TEXT
CHECK (openai_request IS NULL OR json_valid(openai_request))
