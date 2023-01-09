package com.rrarey.web;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import com.rrarey.utils.ExceptionUtils;

public class RESTRequest extends WebRequest {
	private String baseURL;
	
	private static final Logger logger = LogManager.getLogger(RESTRequest.class);	

	public RESTRequest() {
		super();
	}

	public RESTRequest(String b) {
		super();
		setBaseUrl(b);
	}
	
	public RESTRequest setBaseUrl(String b) {
		if (b != null) {
			b = b.trim();
			if (!b.endsWith("/")) {
				b = b + "/";
			}
		}
		
		this.baseURL = b;
		
		return this;
	}

	/**
	 * Get a JSONObject representing the content retrieved from the REST endpoint
	 * @param method Method to call at endpoint (assumed to be whatever comes after $BASEURL/rest/api/2/)
	 * @return JSONObject from retrieved content. Note that if an array is retrieved, a JSONObject of the format {d: content} will be returned.
	 */
	public JSONObject requestJSON(String method) throws Exception {
		String json = requestData(method);
		return processResponseData(json, method);
	}

	/**
	 * Convert response data string to JSONObject
	 * @param json Response data string (assumed to be JSON)
	 * @param method Name of REST API endpoint called
	 * @return JSONObject or null if string could not be parsed to JSON.
	 */
	private JSONObject processResponseData(String json, String method) {
		if (json != null && (json.startsWith("{") || json.startsWith("["))) {
			try {
				if (json.startsWith("[")) {
					return new JSONObject("{d:" + json + "}");
				}
				return new JSONObject(json);
			} catch (JSONException e) {
				logger.error("Exception while parsing REST JSON response: {}", ExceptionUtils.getExceptionString(e));
			}
		}

		return null;
	}	
	
	/**
	 * Request data from a particular endpoint
	 * @param method Endpoint
	 * @return String of content retrieved from endpoint
	 */
	private String requestData(String method) throws Exception {
		String fullRequest = baseURL + method;
    	WebRequest w = new WebRequest();
    	w.setBearer(bearer);
    	String response = w.get(fullRequest);

    	return response;
	}
}
