package com.core.automation.model;

import org.apache.commons.lang.builder.ToStringBuilder;

public class HeaderPojo {

	private String key;
	private String value;

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
	
	@Override
	public String toString()
	{
	  return ToStringBuilder.reflectionToString(this);
	}
	

}
