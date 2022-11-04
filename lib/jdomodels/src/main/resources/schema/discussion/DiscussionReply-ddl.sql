CREATE TABLE IF NOT EXISTS `DISCUSSION_REPLY` (
  `ID` BIGINT NOT NULL,
  `THREAD_ID` BIGINT NOT NULL,
  `ETAG` char(36) NOT NULL,
  `CREATED_ON` TIMESTAMP NOT NULL,
  `CREATED_BY` BIGINT NOT NULL,
  `MODIFIED_ON` TIMESTAMP NOT NULL,
  `MESSAGE_KEY` varchar(100) NOT NULL,
  `IS_EDITED` boolean NOT NULL,
  `IS_DELETED` boolean NOT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE (`MESSAGE_KEY`),
  CONSTRAINT `DISCUSSION_REPLY_THREAD_ID_FK` FOREIGN KEY (`THREAD_ID`) REFERENCES `DISCUSSION_THREAD` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `DISCUSSION_REPLY_CREATED_BY_FK` FOREIGN KEY (`CREATED_BY`) REFERENCES `USER_GROUP` (`ID`) ON DELETE CASCADE
)
