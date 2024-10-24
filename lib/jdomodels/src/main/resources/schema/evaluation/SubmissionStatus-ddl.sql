CREATE TABLE IF NOT EXISTS EVALUATION_SUBMISSION_STATUS (
    ID BIGINT NOT NULL,
	ETAG char(36) NOT NULL,
	SUBSTATUS_VERSION BIGINT NOT NULL,
    MODIFIED_ON BIGINT NOT NULL,
    STATUS int NOT NULL,
    ANNOTATIONS JSON,
    SCORE double DEFAULT NULL,
    ENTITY_JSON JSON,
    PRIMARY KEY (ID),
    FOREIGN KEY (ID) REFERENCES EVALUATION_SUBMISSION (ID) ON DELETE CASCADE
);
