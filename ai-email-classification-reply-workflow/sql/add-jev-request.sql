ALTER TABLE email_results ADD COLUMN jev_request TEXT
CHECK (jev_request IS NULL OR json_valid(jev_request))
