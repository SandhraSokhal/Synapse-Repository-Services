package org.sagebionetworks.csv.utils;

import java.util.LinkedList;
import java.util.List;


/**
 * A simple test class for ObjectCSV reading and writing.
 * @author jmhill
 *
 */
public class ExampleObject {
	public enum SomeEnum { A, B, C };
	public static String A_STATIC_FIEDS = "Static Fields should not be included";

	private String aString;
	private Long aLong;
	private Boolean aBoolean;
	private Double aDouble;
	private Integer anInteger;
	private Float aFloat;
	private SomeEnum someEnum;
	
	public SomeEnum getSomeEnum() {
		return someEnum;
	}
	public void setSomeEnum(SomeEnum someEnum) {
		this.someEnum = someEnum;
	}
	public Float getaFloat() {
		return aFloat;
	}
	public void setaFloat(Float aFloat) {
		this.aFloat = aFloat;
	}
	public Double getaDouble() {
		return aDouble;
	}
	public void setaDouble(Double aDouble) {
		this.aDouble = aDouble;
	}
	public Integer getAnInteger() {
		return anInteger;
	}
	public void setAnInteger(Integer anInteger) {
		this.anInteger = anInteger;
	}
	public String getaString() {
		return aString;
	}
	public void setaString(String aString) {
		this.aString = aString;
	}
	public Long getaLong() {
		return aLong;
	}
	public void setaLong(Long aLong) {
		this.aLong = aLong;
	}
	public Boolean getaBoolean() {
		return aBoolean;
	}
	public void setaBoolean(Boolean aBoolean) {
		this.aBoolean = aBoolean;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((aBoolean == null) ? 0 : aBoolean.hashCode());
		result = prime * result + ((aDouble == null) ? 0 : aDouble.hashCode());
		result = prime * result + ((aFloat == null) ? 0 : aFloat.hashCode());
		result = prime * result + ((aLong == null) ? 0 : aLong.hashCode());
		result = prime * result + ((aString == null) ? 0 : aString.hashCode());
		result = prime * result
				+ ((anInteger == null) ? 0 : anInteger.hashCode());
		result = prime * result
				+ ((someEnum == null) ? 0 : someEnum.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ExampleObject other = (ExampleObject) obj;
		if (aBoolean == null) {
			if (other.aBoolean != null)
				return false;
		} else if (!aBoolean.equals(other.aBoolean))
			return false;
		if (aDouble == null) {
			if (other.aDouble != null)
				return false;
		} else if (!aDouble.equals(other.aDouble))
			return false;
		if (aFloat == null) {
			if (other.aFloat != null)
				return false;
		} else if (!aFloat.equals(other.aFloat))
			return false;
		if (aLong == null) {
			if (other.aLong != null)
				return false;
		} else if (!aLong.equals(other.aLong))
			return false;
		if (aString == null) {
			if (other.aString != null)
				return false;
		} else if (!aString.equals(other.aString))
			return false;
		if (anInteger == null) {
			if (other.anInteger != null)
				return false;
		} else if (!anInteger.equals(other.anInteger))
			return false;
		if (someEnum != other.someEnum)
			return false;
		return true;
	}
	@Override
	public String toString() {
		return "ExampleObject [aString=" + aString + ", aLong=" + aLong
				+ ", aBoolean=" + aBoolean + ", aDouble=" + aDouble
				+ ", anInteger=" + anInteger + ", aFloat=" + aFloat + "]";
	}
	
	/**
	 * Build some example objects.
	 * @param count
	 * @return
	 */
	public static List<ExampleObject> buildExampleObjectList(int count) {
		List<ExampleObject> data = new LinkedList<ExampleObject>();
		for(int i=0; i<count; i++){
			ExampleObject ob = new ExampleObject();
			ob.setaBoolean(i%2 == 0);
			ob.setaString("Value,"+i);
			ob.setaLong(new Long(11*i));
			ob.setaDouble(12312312.34234/i);
			ob.setAnInteger(new Integer(i));
			ob.setaFloat(new Float(123.456*i));
			ob.setSomeEnum(SomeEnum.A);
			// Add some nulls
			if(i%3 == 0){
				ob.setaBoolean(null);
			}
			if(i%4 == 0){
				ob.setaString(null);
			}
			if(i%5 == 0){
				ob.setaLong(null);
			}
			data.add(ob);
		}
		return data;
	}
}
