CREATE TABLE IF NOT EXISTS `PREVIEW_BLOB` (
  `OWNER_NODE_ID` BIGINT NOT NULL,
  `TOKEN_ID` BIGINT NOT NULL,
  `PREVIEW_BLOB` mediumblob,
  PRIMARY KEY (`OWNER_NODE_ID`,`TOKEN_ID`),
  CONSTRAINT `PREVIEW_OWNER_FK` FOREIGN KEY (`OWNER_NODE_ID`) REFERENCES `NODE` (`ID`) ON DELETE CASCADE
)
