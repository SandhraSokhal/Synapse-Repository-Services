CREATE TABLE IF NOT EXISTS `OAUTH_REFRESH_TOKEN` (
  `ID` BIGINT NOT NULL,
  `TOKEN_HASH` CHAR(64) NOT NULL,
  `NAME` VARCHAR(256) NOT NULL,
  `PRINCIPAL_ID` BIGINT NOT NULL,
  `CLIENT_ID` BIGINT NOT NULL,
  `SCOPES` MEDIUMBLOB NOT NULL,
  `CLAIMS` MEDIUMBLOB NOT NULL,
  `LAST_USED` TIMESTAMP(3) NOT NULL,
  `CREATED_ON` TIMESTAMP(3) NOT NULL,
  `MODIFIED_ON` TIMESTAMP(3) NOT NULL,
  `ETAG` char(36) NOT NULL,
  PRIMARY KEY (`ID`),
  CONSTRAINT `OAUTH_REFRESH_TOKEN_PRINCIPAL_RESOURCE_OWNER_FK` FOREIGN KEY (`PRINCIPAL_ID`) REFERENCES `USER_GROUP` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `OAUTH_REFRESH_TOKEN_CLIENT_ID_FK` FOREIGN KEY (`CLIENT_ID`) REFERENCES `OAUTH_CLIENT` (`ID`) ON DELETE CASCADE,
  UNIQUE (`PRINCIPAL_ID`, `NAME`)
)
