package org.sagebionetworks.repo.model.annotation.v2;

import java.util.Date;
import java.util.Map;
import java.util.function.Function;

import org.sagebionetworks.repo.model.Annotations;
import org.sagebionetworks.util.doubles.DoubleUtils;

enum AnnotationV1AndV2TypeMapping {

	STRING(String.class, AnnotationsV2ValueType.STRING, Function.identity(), Function.identity(), Annotations::getStringAnnotations),

	//DoubleUtils.fromString handles other representations of Infinity and Nan, unlike Java's built in Double.valueOf
	DOUBLE(Double.class, AnnotationsV2ValueType.DOUBLE, Object::toString, DoubleUtils::fromString, Annotations::getDoubleAnnotations),
	LONG(Long.class, AnnotationsV2ValueType.LONG, Object::toString, Long::valueOf, Annotations::getLongAnnotations),
	DATE(Date.class, AnnotationsV2ValueType.TIMESTAMP_MS,
			(Date date) -> Long.toString(date.getTime()),
			(String timestampMillis) -> new Date(Long.parseLong(timestampMillis)),
			Annotations::getDateAnnotations);

	private final Class<?> javaClass;
	private final AnnotationsV2ValueType valueType;
	private final Function<?, String> toAnnotationV2Function;
	private final Function<String, ?> toAnnotationV1Function;
	private final Function<Annotations, Map> annotationV1MapGetter;


	<T> AnnotationV1AndV2TypeMapping(Class<T> javaClass,
									 AnnotationsV2ValueType valueType,
									 Function<T, String> toAnnotationV2Function,
									 Function<String, T> toAnnotationV1Function,
									 Function<Annotations, Map> annotationV1MapGetter) {
		this.javaClass = javaClass;
		this.valueType = valueType;
		this.toAnnotationV2Function = toAnnotationV2Function;
		this.toAnnotationV1Function = toAnnotationV1Function;
		this.annotationV1MapGetter = annotationV1MapGetter;
	}

	public <T> Function<String, T> convertToAnnotationV1Function(){
		return (Function<String, T>) toAnnotationV1Function;
	}

	public <T> Function<T, String> convertToAnnotationV2Function(){
		return (Function<T, String>) toAnnotationV2Function;
	}

	public Function<Annotations, Map> annotationV1MapGetter() {
		return annotationV1MapGetter;
	}

	public AnnotationsV2ValueType getValueType(){
		return valueType;
	}

	public static AnnotationV1AndV2TypeMapping forClass(Class clazz){
		for(AnnotationV1AndV2TypeMapping annotationV1AndV2TypeMapping : values()){
			if(annotationV1AndV2TypeMapping.javaClass == clazz){
				return annotationV1AndV2TypeMapping;
			}
		}
		throw new IllegalArgumentException("No mapping for " + clazz + " to a AnnotationV1AndV2TypeMapping");
	}

	public static AnnotationV1AndV2TypeMapping forValueType(AnnotationsV2ValueType valueType){
		for(AnnotationV1AndV2TypeMapping annotationV1AndV2TypeMapping : values()){
			if(annotationV1AndV2TypeMapping.valueType == valueType){
				return annotationV1AndV2TypeMapping;
			}
		}
		throw new IllegalArgumentException("No mapping for " + valueType + " to a AnnotationV1AndV2TypeMapping");
	}
}
