CREATE TABLE IF NOT EXISTS `COLUMN_MODEL` (
  `ID` bigint(20) NOT NULL,
  `NAME` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `HASH` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `BYTES` mediumblob,
  PRIMARY KEY (`ID`),
  KEY `CM_NAME_INDEX` (`NAME`),
  UNIQUE KEY `UNIQUE_CM_HASH` (`HASH`)
)
