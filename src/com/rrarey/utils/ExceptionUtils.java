package com.rrarey.utils;

public class ExceptionUtils {
	/**
	 * Get a String representing Throwable (exception) information, for logging purposes
	 * @param e Throwable object
	 * @return String representation of Throwable object
	 */
	public static String getExceptionString(Throwable e) {
		String message = "";
		String stackTrace = "";

		if (e != null) {
			message = e.getMessage() != null ? e.getMessage() : "";
			stackTrace =
				org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e) != null ?
				org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e) : "";
		}

		String s = "";
		if (message != null && message.toString().length() > 0) {
			s = message;
		}

		if (stackTrace != null && stackTrace.toString().length() > 0) {
			if (s.length() > 0) {
				s += ": ";
			}
			s += stackTrace;
		}

		return s;
	}
}
