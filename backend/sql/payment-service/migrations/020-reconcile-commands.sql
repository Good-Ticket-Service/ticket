CREATE TABLE IF NOT EXISTS p_reconcile_commands (
 command_id UUID PRIMARY KEY,business_date DATE NOT NULL,mode VARCHAR(10) NOT NULL CHECK(mode='FULL'),
 reason VARCHAR(500) NOT NULL,actor VARCHAR(200) NOT NULL,created_at TIMESTAMP NOT NULL
);
