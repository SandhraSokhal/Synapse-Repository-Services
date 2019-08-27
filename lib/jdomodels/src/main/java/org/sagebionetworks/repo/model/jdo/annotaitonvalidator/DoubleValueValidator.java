package org.sagebionetworks.repo.model.jdo.annotaitonvalidator;

import org.sagebionetworks.util.doubles.DoubleUtils;

class DoubleValueValidator implements AnnotationsV2ValueValidator {
	@Override
	public boolean isValidValue(String value) {
		try {
			DoubleUtils.fromString(value);
			return true;
		} catch (NumberFormatException e){
			return false;
		}
	}
}
