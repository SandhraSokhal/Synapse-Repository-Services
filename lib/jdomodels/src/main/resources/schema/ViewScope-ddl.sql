CREATE TABLE IF NOT EXISTS `VIEW_SCOPE` (
  `VIEW_ID` bigint(20) NOT NULL,
  `CONTAINER_ID` bigint(20) NOT NULL,
  PRIMARY KEY (`VIEW_ID`,`CONTAINER_ID`),
  INDEX (`VIEW_ID`),
  INDEX (`CONTAINER_ID`),
  CONSTRAINT `VIEW_TYPE_FK` FOREIGN KEY (`VIEW_ID`) REFERENCES `VIEW_TYPE` (`VIEW_ID`) ON DELETE CASCADE
)
