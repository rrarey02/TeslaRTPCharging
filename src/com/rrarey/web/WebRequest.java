package com.rrarey.web;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.rrarey.utils.ExceptionUtils;

public class WebRequest {

	protected ArrayList<Cookie> cookies = new ArrayList<Cookie>();
	protected String bearer;
	
	private static final Logger logger = LogManager.getLogger(WebRequest.class);		

	public WebRequest() {
		CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
	}

	/**
	 * Set the cookies to be used for requests
	 * @param cookies list of Cookies
	 */
	public void setCookies(ArrayList<Cookie> cookies) {
		this.cookies = cookies;
	}

	/**
	 * Set the bearer token to be used for requests
	 * @param b Bearer token string
	 */
	public void setBearer(String b) {
		this.bearer = b;
	}
	
	/**
	 * Perform a GET request on a provided URL
	 * @param getURL URL to request
	 * @return String of content retrieved from the URL
	 */
	public String get(String getURL) throws Exception {
        URL url;

        StringWriter returnData = new StringWriter();
        
        HttpURLConnection conn = null;
        InputStreamReader ir = null;
        
        logger.debug("GET request to: {}", getURL);

        try {
            url = new URL(getURL);
            conn = (HttpURLConnection)url.openConnection();
            conn.setReadTimeout(30000);
        	conn.setConnectTimeout(15000);
        	conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        	conn.setRequestProperty("Content-Type", "application/json");        	
        	conn.setRequestProperty("User-Agent", "");        	
        	conn.setRequestProperty("x-tesla-user-agent", "");
        	conn.setRequestProperty("X-Requested-With", "com.teslamotors.tesla");
        	if (bearer != null) {
        		conn.setRequestProperty("Authorization", "Bearer " + bearer);
        	}

        	try {
        		conn.connect();
        	} catch (Exception ex) { 
        		//logger.error("Could not connect to {} for GET request: {}",  getURL,  ExceptionUtils.getExceptionString(ex));
        	}

        	try {
        		ir = new InputStreamReader(conn.getInputStream(), "UTF-8");
        	} catch (Exception e2) {
        		throw e2;
        	}
            
            if (ir != null) {
	            char[] buffer = new char[1024 * 4];
	            int len = 0;
	            while ((len = ir.read(buffer)) != -1) {
	            	returnData.write(buffer, 0, len);
	            }
            }
            
            processHeaderCookies(conn);

        } catch (Exception e) {
        	throw e;
        } finally {
        	if (ir != null) {
        		try {
        			ir.close();
        		} catch (IOException e) { }
        	}
        	if (conn != null) {
        		conn.disconnect();
        	}
        }

        returnData.flush();
        String returnText = returnData.toString();
        
        try {
        	returnData.close();
        } catch (IOException e) { }
        
        return returnText;
	}
	
	/**
	 * Convenience method to return JSONObject from GET request.
	 * @see get
	 * @param getUrl URL to request
	 * @return JSONObject from reponse. Null if failure in request or non-JSON response received.
	 */
	public JSONObject getJSON(String getUrl) throws Exception {
		String responseText = get(getUrl);
		JSONObject responseJSON = null;
		try {
			responseJSON = new JSONObject(responseText);
		} catch (Exception ex) { }

		return responseJSON;
	}
	
	/**
	 * Convenience method to perform a POST that does not require a body.
	 * @see post(String, String)
	 * @param postURL URL for POST
	 * @return Result of POST
	 * @throws Exception When things go wrong
	 */
	public String post(String postURL) throws Exception {
		return post(postURL, null);
	}
	
	/**
	 * Perform a POST with a body
	 * @param postURL URL for POST
	 * @param body String for POST body
	 * @return Result of POST
	 * @throws Exception When things go wrong
	 */	
	public String post(String postURL, String body) throws Exception {
        URL url;

        StringWriter returnData = new StringWriter();
        
        HttpURLConnection conn = null;
        InputStreamReader ir = null;
        
        logger.debug("POST request to: {}", postURL);
        if (body != null) {
        	logger.debug("POST body: {}", body);
        }
        
        try {
            url = new URL(postURL);
            conn = (HttpURLConnection)url.openConnection();
            conn.setReadTimeout(30000);
        	conn.setConnectTimeout(15000);
        	conn.setRequestMethod("POST");
        	HttpURLConnection.setFollowRedirects(false);
        	conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        	conn.setRequestProperty("Content-Type", "application/json");
        	if (body != null && body.length() > 0) {
        		conn.setRequestProperty("Content-Length", String.valueOf(body.length()));
        	}
        	conn.setRequestProperty("User-Agent", "");        	
        	conn.setRequestProperty("x-tesla-user-agent", "");
        	conn.setRequestProperty("X-Requested-With", "com.teslamotors.tesla");        	
        	conn.setDoOutput(true);

        	if (bearer != null) {
        		conn.setRequestProperty("Authorization", "Bearer " + bearer);
        	}        	

        	try {
        		conn.connect();
        	} catch (Exception ex) { 
        		//logger.error("Could not connect to {} for POST request: {}", postURL, ExceptionUtils.getExceptionString(ex));
        	}
            
    		if (body != null && body.length() > 0) {
    			OutputStreamWriter out = null;
	    		try {
	    			out = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");			
    				out.write(body);
    				out.flush();
	    		} catch (IOException e1) {
	    			logger.error("Exception while POSTing body to {}: {}", postURL, ExceptionUtils.getExceptionString(e1));
	    			throw e1;
	    		} finally {
	    			if (out != null) {
	    				out.close();
	    			}
	    		}
    		}

    		try {
            	ir = new InputStreamReader(conn.getInputStream(), "UTF-8");
            } catch (IOException e2) { 
            	throw e2;
            }
            
            if (ir != null) {
	            char[] buffer = new char[1024 * 4];
	            int len = 0;
	            while ((len = ir.read(buffer)) != -1) {
	            	returnData.write(buffer, 0, len);
	            }
            }
            
            processHeaderCookies(conn);        

        } catch (Exception e) {
        	throw e;
        } finally {
        	if (ir != null) {
        		try {
        			ir.close();
        		} catch (IOException e) { }
        	}
        	if (conn != null) {
        		conn.disconnect();
        	}
        }

        returnData.flush();
        String returnText = returnData.toString();
        
        try {
        	returnData.close();
        } catch (IOException e) { }
        
        return returnText;
	}
	
    private void processHeaderCookies(HttpURLConnection conn) {
        Map<String, List<String>> headerFields = conn.getHeaderFields();
        List<String> headerCookies = headerFields.get("Set-Cookie");
        if (headerCookies != null) {
        	for(int i = 0; i < headerCookies.size(); i++) {
        		String cookie = headerCookies.get(i);
        		String[] parts = cookie.split("=", 2);
        		if (parts.length > 1) {
        			cookies.add(new Cookie(parts[0], parts[1]));
        		}
        	}
        }
    }	
}
