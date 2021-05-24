SELECT 
	NAR.*,
	if(AA.STATE = 'APPROVED', TRUE, FALSE) AS APPROVED,
	AR.CONCRETE_TYPE AS REQUIREMENT_TYPE
	FROM NODE_ACCESS_REQUIREMENT NAR
		LEFT JOIN ACCESS_APPROVAL AA 
			ON (NAR.REQUIREMENT_ID = AA.REQUIREMENT_ID AND AA.ACCESSOR_ID = :userId AND AA.STATE = 'APPROVED')
				LEFT JOIN ACCESS_REQUIREMENT AR
					ON (NAR.REQUIREMENT_ID = AR.ID)
						WHERE NAR.SUBJECT_ID IN (:subjectIds) AND NAR.SUBJECT_TYPE = :subjectType;