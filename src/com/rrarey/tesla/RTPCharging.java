package com.rrarey.tesla;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.TimeZone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rrarey.utils.ExceptionUtils;
import com.rrarey.web.RESTRequest;
import com.rrarey.web.WebRequest;

public class RTPCharging {
	static final String programVersion = "1.0.3";
	
	// Tesla API base URL
	static final String apiBase = "https://owner-api.teslamotors.com";

	static final String propertiesFile = "app.properties";

	// Property file keys
	static final String
		ACCESS_TOKEN = "ACCESS_TOKEN",
		HOME_LATITUDE = "HOME_LATITUDE",
		HOME_LONGITUDE = "HOME_LONGITUDE",
		MAX_ELECTRICITY_PRICE = "MAX_ELECTRICITY_PRICE",
		MINIMUM_DEPARTURE_SOC = "MINIMUM_DEPARTURE_SOC",
		POLL_INTERVAL_SECONDS = "POLL_INTERVAL_SECONDS",
		REFRESH_TOKEN = "REFRESH_TOKEN",
		RESTART_ON_CURRENT_DROP = "RESTART_ON_CURRENT_DROP",
		SOC_GAIN_PER_HOUR = "SOC_GAIN_PER_HOUR",
		VIN = "VIN"
	;

	// API call retry settings
	static final int
		MAX_RETRIES = 5,
		RETRY_INTERVAL_SECONDS = 15
	;

	// Set in properties file or command line argument
	static double
		homeLatitude,
		homeLongitude,
		maxElectricityPrice,
		soCGainPerHour
	;
	static String
		accessToken,
		id,
		refreshToken,
		vin
	;
	static int
		minimumDepartureSoC,
		pollIntervalSeconds
	;
	static boolean
		restartOnCurrentDrop = false,
		shouldChargeForDepature = false
	;
	static long
		lastConfigurationModification = 0
	;

	// Vehicle location history
	static CircularFifoQueue<VehicleLocation> vehicleLocationHistory = new CircularFifoQueue<VehicleLocation>(250);
	static VehicleLocation lastHome = null;

	// Objects for API calls
	static RESTRequest teslaAPI = null;
	static WebRequest teslaCommands = null;

    static final Logger logger = LogManager.getLogger(RTPCharging.class);

	public static void main(String[] args) {
		logger.debug("Loading configuration");
		loadConfiguration();

		logger.debug("Setting up API objects");
		setupAPIObjects();

		logger.debug("Requesting vehicle match");
		JSONObject vehicleMatch = getVehicleMatchForVIN();

		String displayName = null;
		if (vehicleMatch == null) {
			exitWithError("Vehicle not found in API response.");
		}

		id = vehicleMatch.getString("id_s");
		if (vehicleMatch.has("display_name")) {
			displayName = vehicleMatch.getString("display_name");
		}

		TimeZone tz = TimeZone.getTimeZone("America/Chicago");
		DateFormat df = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss a");
		df.setTimeZone(tz);

		logger.debug("Checking vehicle charging state");
		boolean isCharging = isVehicleCharging(id);

		// Flag to set when vehicle reports it is fully charged.
		boolean wasFullyCharged = false;

		// Flag to set when vehicle goes to sleep at home.
		boolean asleepAtHome = false;

		String previousVehicleState = "unknown";

		long comEdLastUTC = 0;
		int defaultLocationPollingSeconds = (5 * 60);	// Default to checking location every five minutes

		// Next time we are going to check on the car's location. Seconds since January 1, 1970.
		long nextLocationCheckSeconds = 0;

		log("Starting ComEd Real-Time price monitoring version " + programVersion + " for Tesla VIN " + vehicleMatch.getString("vin") + " (" + ((displayName != null && displayName.length() > 0) ? displayName : id) + ").");
		log("Polling for new price data every " + pollIntervalSeconds + " seconds.");
		log("Vehicle will" + (!shouldChargeForDepature ? " not" : "") + " be charged to reach minimum departure SoC" + (minimumDepartureSoC > 0 ? " of " + minimumDepartureSoC + "%" : "") + ".");
		log("Vehicle is" + (!isCharging ? " not" : "") + " currently charging.");
		logger.debug("Starting main loop");

		// Main loop
		while(true) {
			JSONObject currentData = getLatestComEdPrice();
			if (currentData == null) {
				sleep(RETRY_INTERVAL_SECONDS);
				continue;
			}

			String currentVehicleState = getVehicleState(id);

			// Location polling logic:
			//	1) Always starts from home
			//	2) Watches for car to move and records those locations
			//	3) Starts looking at location history on default polling interval
			//	4) Watches for car to be one position for two default polling intervals
			//	5) Increases polling interval to the amount of time it took car to leave home and arrive at stop location, or the default polling interval, whichever is greater.
			//	6) Continues checking vehicle state, hopefully it is asleep.
			//	7) Car starts moving again or comes back online - restore default polling interval
			//	8) TODO: Car appears to be moving closer to home - adjust polling interval
			//	9) Car arrives back at home. Restore default polling interval

			boolean updatedLocation = false;
			boolean updateLocationIfOnline = false;
			VehicleLocation currentLocation = null, previousLocation = null;
			int secondsFromHomeToStop = 0;
			int locationHistorySize = vehicleLocationHistory.size();
			if (locationHistorySize > 0) {
				previousLocation = vehicleLocationHistory.get(locationHistorySize - 1);
			}
			if (locationHistorySize > 1) {
				// TODO: Need to recognize multiple stops during trip away from home - not just the latest stop - and act accordingly.
				logger.debug("Multiple entries in location history, Doing some additional checks.");
				VehicleLocation previousLocationInHistory = null;
				VehicleLocation stoppedAtLocation = null;
				for(int i = 0; i < locationHistorySize; i++) {
					VehicleLocation currentLocationInHistory = vehicleLocationHistory.get(i);
					logger.debug("Location: {}", currentLocationInHistory.toString());
					if (previousLocationInHistory != null && currentLocationInHistory.distanceFrom(previousLocationInHistory) == 0) {
						// Only set stopped location the first time we encounter the match, so we can know the time it took to go from
						// home to the location.
						if (stoppedAtLocation == null) {
							stoppedAtLocation = previousLocationInHistory;
						}
					} else {
						stoppedAtLocation = null;
					}
					previousLocationInHistory = currentLocationInHistory;
				}

				if (stoppedAtLocation != null) {
					logger.debug("Vehicle seems to have stopped at: {}", stoppedAtLocation.toString());
				} else {
					logger.debug("Vehicle is not stopped.");
				}

				// Vehicle is navigating to home - use that to schedule the next location poll.
				if (previousLocationInHistory != null && previousLocationInHistory.isGoingHome()) {
					nextLocationCheckSeconds = (long)Math.floor(previousLocationInHistory.getArrivalTimeSeconds());
					logger.debug(
						"Vehicle is navigating to home as of {}, with an expected arrival time of {}. Scheduling next location check for {}.",
						df.format(new Date((long)previousLocationInHistory.getTimestampMillis())),
						df.format(new Date(nextLocationCheckSeconds * 1000)),
						df.format(new Date(nextLocationCheckSeconds * 1000))
					);

				// Vehicle is navigating away from home - use that to schedule the next location poll.
				} else if (previousLocationInHistory != null && previousLocationInHistory.isGoingAwayFromHome()) {
					nextLocationCheckSeconds = (long)Math.floor(previousLocationInHistory.getArrivalTimeSeconds());
					logger.debug(
						"Vehicle is navigating away from home as of {}, with an expected arrival time of {}. Scheduling next location check for {}.",
						df.format(new Date((long)previousLocationInHistory.getTimestampMillis())),
						df.format(new Date(nextLocationCheckSeconds * 1000)),
						df.format(new Date(nextLocationCheckSeconds * 1000))
					);

				// Vehicle is stopped - use travel time from home to this stop to schedule the next location poll.
				} else if (stoppedAtLocation != null && lastHome != null) {
					// Next location check will be 75% of the travel time from home to stop, after vehicle was stopped.
					// Unless it was a short time, in which case we'll keep the default.
					secondsFromHomeToStop = stoppedAtLocation.getTimestamp() - lastHome.getTimestamp();
					logger.debug("Vehicle was at home and is now stopped at {}. It took {} seconds to go from home to stop.", stoppedAtLocation, secondsFromHomeToStop);
					if (((double)secondsFromHomeToStop * .75) < defaultLocationPollingSeconds) {
						secondsFromHomeToStop = (int)Math.floor((double)defaultLocationPollingSeconds / .75);
					}

					long newNextLocationCheckSeconds = (long)Math.floor(stoppedAtLocation.getTimestamp() + ((double)secondsFromHomeToStop * .75));
					if (newNextLocationCheckSeconds >= nextLocationCheckSeconds) {
						nextLocationCheckSeconds = newNextLocationCheckSeconds;
					}
					logger.debug("Next location check will be at {}, IF VEHICLE IS ONLINE.", df.format(new Date(nextLocationCheckSeconds * 1000)));
					updateLocationIfOnline = true;
				}
			}

			// Vehicle was most recently seen at home. Only make additional calls if it's online.
			// This way you can leave the car sitting at home, not plugged in, and we won't drain the battery.
			if (previousLocation != null && previousLocation.distanceFrom(homeLatitude, homeLongitude) == 0) {
				updateLocationIfOnline = true;

			// Vehicle is away from home - reset charged flag so we can determine it again when we get home.
			} else {
				wasFullyCharged = false;
			}

			// Check if vehicle was previously not online, but now it is online.
			boolean vehicleIsNowOnline = (currentVehicleState.equals("online") && !previousVehicleState.equals("online"));

			// Run location poll when necessary. Either the scheduled poll time says we should run it,
			// or the vehicle has recently come back online.
			if (nextLocationCheckSeconds <= currentTime() || vehicleIsNowOnline) {
				if (vehicleIsNowOnline) {
					updateLocationIfOnline = false;
					logger.debug("Executing location check because vehicle has recently come back online.");
				} else {
					logger.debug("Next location check scheduled for {} should be executed.", df.format(new Date(nextLocationCheckSeconds * 1000)));
				}
				if (updateLocationIfOnline) {
					logger.debug("Will only update location if vehicle is online.");
					if (currentVehicleState.equals("online")) {
						logger.debug("Vehicle is online.");
						currentLocation = updateVehicleLocationDetails(id);
						updatedLocation = true;
					} else {
						log("Vehicle is not online, so we will let it sleep and update location again when the vehicle is online.");
						currentLocation = previousLocation;
					}
				} else {
					currentLocation = updateVehicleLocationDetails(id);
					updatedLocation = true;
				}

				if (secondsFromHomeToStop > 0) {
					nextLocationCheckSeconds += Math.floor((double)secondsFromHomeToStop * .75);
				} else {
					// If vehicle was fully charged and didn't just come back online, schedule next location check
					// further out so the vehicle can go to sleep. Otherwise use default scheduling.
					if (wasFullyCharged && !vehicleIsNowOnline) {
						nextLocationCheckSeconds = (currentTime() + (20 * 60));
					} else {
						nextLocationCheckSeconds = (currentTime() + defaultLocationPollingSeconds);
					}
				}
			} else {
				currentLocation = previousLocation;
			}

			// This shouldn't happen, but in case it does...
			if (currentLocation == null) {
				currentLocation = updateVehicleLocationDetails(id);
				
				if (currentLocation == null) {
					sleep(RETRY_INTERVAL_SECONDS);
					continue;
				}
			}

			double locationTime = currentLocation.getTimestampMillis();
			double distanceFromHome = currentLocation.distanceFrom(homeLatitude, homeLongitude);

			if (updatedLocation) {
				log("Vehicle is " + distanceFromHome + " miles from home as of " + df.format(new Date((long)locationTime)));
			}

			logger.debug("Next vehicle location check scheduled for: {}", df.format(new Date(nextLocationCheckSeconds * 1000)));

			long comEdCurrentUTC = Long.parseLong(currentData.getString("millisUTC"));
			double currentPrice = currentData.getDouble("price");
			boolean newData = false;

			if (comEdCurrentUTC != comEdLastUTC) {
				Date date = new Date(comEdCurrentUTC);
				log("ComEd 5-minute price (" + currentPrice + "\u00A2 / kWh) from " + df.format(date) + " is " + (currentPrice <= maxElectricityPrice ? "valid for charging (<= " + maxElectricityPrice : "not valid for charging (> " + maxElectricityPrice) + "\u00A2 / kWh)");
				comEdLastUTC = comEdCurrentUTC;
				newData = true;
			}

			String chargingState = "";			// "Stopped" when not charging but plugged in. "Disconnected" when unplugged. "Charging" when charging.
			boolean chargePortOpen = false;		// true when door is open. Else false.
			boolean stopStartCharge = false;	// Set to true when we determine we need to stop and restart charging to get the rate back up.
			boolean forceCharging = false;		// Set to true when time and departure SoC dictate.

			int minutesToFullCharge = 0;		// Will be set by charge_state response if possible
			double currentBatteryLevel = 0;		// Will be set by charge_state response if possible
			double chargeLimit = 90;			// Will be set by charge_state response if possible
			int minutesToDeparture = 0;			// Will be set by charge_state response if possible

			// In any of these cases we need to make sure the vehicle is awake, and determine charging setup and status.
			// 1) We have new data AND:
			//		a) The current ComEd price is <= our max price AND
			//			i)  The vehicle is not fully charged AND
			//			ii) The vehicle is not currently charging, OR restart on current drop is enabled
			//		OR
			//		b) The current ComEd price is > our max price AND
			//			i)  The vehicle is charging
			if (newData && distanceFromHome == 0) {
				JSONObject chargeStateResponse = null;

				if ((currentPrice <= maxElectricityPrice && (!isCharging || restartOnCurrentDrop)) || (currentPrice > maxElectricityPrice && isCharging)) {
					log("Current vehicle state: " + currentVehicleState);

					if (wasFullyCharged) {
						boolean isCurrentlySleeping = currentVehicleState.equals("asleep");
						logger.debug("Vehicle was fully charged at last check. Trying to let it sleep.");
						if (asleepAtHome && !isCurrentlySleeping) {
							logger.debug("Vehicle is awake again. Might need to restart charging.");
							asleepAtHome = false;
						} else {
							asleepAtHome = isCurrentlySleeping;
							continue;
						}
					}
				}

				try {
					if (!currentVehicleState.equals("online")) {
						wakeUpVehicle(id);
					}

					int tries = 0;
					while (chargeStateResponse == null && ++tries < MAX_RETRIES) {
						chargeStateResponse = getVehicleChargeState(id);
						if (chargeStateResponse == null) {
							refreshTokens();
							sleep(RETRY_INTERVAL_SECONDS);
						}
					}

					if (chargeStateResponse == null) {
						logger.warn("Did not receive charge state response after multiple attempts.");
						continue;
					}

					if (chargeStateResponse.has("charging_state") && chargeStateResponse.has("charge_port_door_open")) {
						minutesToFullCharge = chargeStateResponse.getInt("minutes_to_full_charge");
						chargingState = chargeStateResponse.getString("charging_state");
						chargePortOpen = chargeStateResponse.getBoolean("charge_port_door_open");
						currentBatteryLevel = chargeStateResponse.getDouble("usable_battery_level");
						chargeLimit = chargeStateResponse.getDouble("charge_limit_soc");

						// Minutes to full charge is only reported if the vehicle is actually charging.
						String fullCharge = "";
						if (minutesToFullCharge > 0) {
							int hours = minutesToFullCharge / 60;
							int minutes = minutesToFullCharge % 60;
							if (hours > 0) {
								fullCharge = " " + String.format("%dh %02dm", hours, minutes) + " remaining to charge limit.";
							} else {
								fullCharge = " " + String.format("%02dm", minutes) + " remaining to charge limit.";
							}
						}

						int timestamp = (int) Math.floor(chargeStateResponse.getDouble("timestamp") / 1000);
						int departureTime = chargeStateResponse.getInt("scheduled_departure_time");
						if (departureTime > timestamp) {
							minutesToDeparture = (int) Math.floor((departureTime - timestamp) / 60);
						}

						log("Charge: " + String.valueOf(currentBatteryLevel) + "%. Charge limit: " + String.valueOf(chargeLimit) + "%." + fullCharge);
						if (minutesToDeparture > 0 && minimumDepartureSoC > 0 && minimumDepartureSoC > currentBatteryLevel) {
							log(minutesToDeparture + " minutes to departure at " + minimumDepartureSoC + "% charge.");

							double soCGainPerMinute = (soCGainPerHour / 60);
							double requiredCharge = minimumDepartureSoC - currentBatteryLevel;
							int minutesToDepartureSoC = (int) Math.ceil(requiredCharge / soCGainPerMinute);
							log("At " + String.format("%.3f", soCGainPerMinute) + "% SoC gain per minute, it will take " + minutesToDepartureSoC + " minutes to reach desired departure SoC.");
							if (minutesToDepartureSoC >= minutesToDeparture) {
								log("To reach minimum departure SoC of " + minimumDepartureSoC + "%, charging is required.");
								forceCharging = true;
							} else {
								forceCharging = false;
							}
						}
					}

					// Charge current we'd like to see
					double requestedChargeAmps = 12;
					if (chargeStateResponse.has("charge_current_request") && !chargeStateResponse.isNull("charge_current_request")) {
						requestedChargeAmps = chargeStateResponse.getDouble("charge_current_request");
					}

					// Charge current we are currently seeing
					double chargerActualCurrent = requestedChargeAmps;
					if (chargeStateResponse.has("charger_actual_current") && !chargeStateResponse.isNull("charger_actual_current")) {
						chargerActualCurrent = chargeStateResponse.getDouble("charger_actual_current");
					}

					if (restartOnCurrentDrop) {
						if (chargerActualCurrent < requestedChargeAmps) {
							stopStartCharge = true;
						}
					}
				} catch (Exception ex) {
					logger.error("Failed to parse charge state response: {}", ExceptionUtils.getExceptionString(ex));
				}

				if (
					chargePortOpen &&
					chargingState != null &&
					chargingState.length() > 0 &&
					!chargingState.equals("Disconnected")
				) {
					wasFullyCharged = false;

					// Checking chargeLimit - 1 because the vehicle will often report that it's done charging at chargeLimit - 1, and
					// attempting to start charging at this point results in an error. Minutes to full charge is reported at 0 before
					// charging starts, so we can't reliably use just that. The OR case should still allow us to reach "full" charge on
					// a car that is actually plugged in and charging.
					if (currentBatteryLevel < (chargeLimit - 1) || minutesToFullCharge > 0) {
						isCharging = chargingState.toLowerCase().equals("charging");
						if (currentPrice <= maxElectricityPrice || forceCharging) {
							if (isCharging && stopStartCharge) {
								log("Charging current has dropped. Attempting to stop and restart.");
								try {
									isCharging = stopCharging(id);
									sleep(15);
									isCharging = startCharging(id);
								} catch (Exception ex) {
									logger.warn("Exception while stopping and restarting charging: {}", ExceptionUtils.getExceptionString(ex));
								}
								if (isCharging) {
									log("Vehicle charging restarted.");
								} else {
									log("Vehicle charging failed to restart.");
								}
							}

							if (!isCharging) {
								isCharging = startCharging(id);
							} else {
								log("Vehicle is currently charging. No action necessary.");
							}
						} else {
							if (isCharging) {
								isCharging = stopCharging(id);
							} else {
								log("Vehicle is not currently charging. No action necessary.");
							}
						}
					} else {
						log("Vehicle is fully charged. No action necessary.");
						wasFullyCharged = true;
						isCharging = false;
					}
				}
			}

			previousVehicleState = currentVehicleState;

			sleep(pollIntervalSeconds);

			loadConfiguration();
		}
	}

	/**
	 * Get the current number of seconds since January 1, 1970
	 * @return Current number of seconds since January 1, 1970
	 */
	private static int currentTime() {
		return (int) Math.floor(Instant.now().toEpochMilli() / 1000);
	}

	/**
	 * Stop execution and exit when we encounter an error and cannot continue.
	 * @param msg Error message to write
	 */
	private static void exitWithError(String msg) {
		msg = msg.trim();
		if (!msg.endsWith(".")) {
			msg += ".";
		}

		logger.fatal(msg + " Exiting.");
		System.exit(0);
	}

	/**
	 * Get the most recnet 5-minute price from ComEd
	 * @return JSONObject with most recent 5-minute price data
	 */
	private static JSONObject getLatestComEdPrice() {
		RESTRequest pricesRequest = null;
		JSONObject pricesResponse = null;

		try {
			pricesRequest = new RESTRequest("https://hourlypricing.comed.com");
			pricesResponse = pricesRequest.requestJSON("api?type=5minutefeed");
		} catch (Exception ex) { }

		if (pricesResponse == null || !pricesResponse.has("d")) {
			return null;
		}

		// Sort the values to get the most recent value.
		// It seems like this is always in position 0 anyway, but we shouldn't trust that.
		JSONArray values = pricesResponse.getJSONArray("d");
		List<JSONObject> sortedValues = new ArrayList<JSONObject>();
		for(int i = 0; i < values.length(); i++) {
			JSONObject value = values.getJSONObject(i);
			if (value != null) {
				sortedValues.add(value);
			}
		}

		if (sortedValues.size() == 0) {
			return null;
		}

		Collections.sort(sortedValues, new Comparator<JSONObject>() {
			@Override
	        public int compare(JSONObject a, JSONObject b) {
	            Long valA = (long) 0;
	            Long valB = (long) 0;

	            try {
	                valA = Long.parseLong(a.getString("millisUTC"));
	                valB = Long.parseLong(b.getString("millisUTC"));
	            }
	            catch (JSONException e) { }

	            return valB.compareTo(valA);
	        }
		});

		return sortedValues.get(0);
	}

	/**
	 * Get the charge state of the vehicle
	 * @param id ID of the vehicle to use when requesting charge state
	 * @return Charge state response JSON object
	 */
	private static JSONObject getVehicleChargeState(String id) {
		JSONObject vehicleDataResponse = getVehicleData(id);
		if (vehicleDataResponse != null && vehicleDataResponse.has("charge_state")) {
			JSONObject data = vehicleDataResponse.getJSONObject("charge_state");
			logger.debug("Charge state response: {}",  data.toString(2));
			return data;
		}
		return null;
	}
	
	/**
	 * Get the full vehicle data set
	 * @param id ID of the vehicle to use when requesting vehicle data
	 * @return Vehicle data response JSON object
	 */	
	private static JSONObject getVehicleData(String id) {
		int tries = 0;
		while(++tries < MAX_RETRIES) {
			JSONObject vehicleDataResponse = null;
			try {
				vehicleDataResponse = teslaAPI.requestJSON("api/1/vehicles/" + id + "/vehicle_data");
			} catch (Exception ex) {
				logger.debug("Vehicle data exception: {}", ExceptionUtils.getExceptionString(ex));
				String errorMessage = ExceptionUtils.getExceptionString(ex);
				if (errorMessage.indexOf("HTTP response code: 408") >= 0) {
					wakeUpVehicle(id);
				} else if (errorMessage.indexOf("HTTP response code: 40") < 0) {
					logger.error("Failed to get vehicle data: {}", ExceptionUtils.getExceptionString(ex));
				} else {
					refreshTokens();
				}
			}

			if (vehicleDataResponse != null && vehicleDataResponse.has("response")) {
				JSONObject data = vehicleDataResponse.getJSONObject("response");
				logger.debug("Vehicle data response: {}", data.toString(2));
				return data;
			}
			sleep(RETRY_INTERVAL_SECONDS);
		}

		return null;
	}	

	/**
	 * Get the drive state of the vehicle
	 * @param id ID of the vehicle to use when requesting drive state
	 * @return Drive state response JSON object
	 */
	private static JSONObject getVehicleDriveState(String id) {
		JSONObject vehicleDataResponse = getVehicleData(id);
		if (vehicleDataResponse != null && vehicleDataResponse.has("drive_state")) {
			JSONObject data = vehicleDataResponse.getJSONObject("drive_state");
			logger.debug("Vehicle data response: {}",  data.toString(2));
			return data;
		}
		return null;
	}

	/**
	 * Get a JSON object with the vehicle details matching the configured VIN.
	 * If no VIN is configured and the account only has one Tesla associated with it, return that vehicle.
	 * @return JSON object with vehicle details
	 */
	private static JSONObject getVehicleMatchForVIN() {
		JSONObject vehicleMatch = null;

		int tries = 0;
		while(vehicleMatch == null && ++tries < MAX_RETRIES) {
			try {
				JSONObject responseJSON = teslaCommands.getJSON(apiBase + "/api/1/vehicles");
				if (responseJSON != null && responseJSON.has("response")) {
					JSONArray vehicles = responseJSON.getJSONArray("response");
					if (vehicles != null && vehicles.length() > 0) {

						// When we only have one vehicle and no VIN, we use the identifier of the vehicle we've found.
						if (vehicles.length() == 1 && (vin == null || vin.length() == 0)) {
							JSONObject vehicle = vehicles.getJSONObject(0);
							if (vehicle != null && vehicle.has("id_s")) {
								vehicleMatch = vehicle;
							}

						// With multiple vehicles or a defined VIN, make sure we find the correct vehicle.
						} else {
							for(int i = 0; i < vehicles.length(); i++) {
								JSONObject vehicle = vehicles.getJSONObject(i);
								if (vehicle != null && vehicle.has("id_s") && vehicle.has("vin")) {
									String thisVIN = vehicle.getString("vin");
									if (thisVIN.equals(vin)) {
										vehicleMatch = vehicle;
										break;
									}
								}
							}
						}
					}
				}
			} catch (JSONException ex) {
				logger.error("Error while handling vehicles response: {}", ExceptionUtils.getExceptionString(ex));
			} catch (Exception ex) {
				String errorMessage = ExceptionUtils.getExceptionString(ex);
				if (errorMessage.indexOf("HTTP response code: 40") > 0) {
					refreshTokens();
				} else {
					logger.warn("Error while attempting to retrieve vehicle identifier for API: {}", errorMessage);
				}
			}

			sleep(RETRY_INTERVAL_SECONDS);
		}

		return vehicleMatch;
	}

	/**
	 * Get the current state of the vehicle with the provided ID.
	 * NOTE! This call won't wake a sleeping Tesla!
	 * @param id ID of the vehicle to use when requesting the current state
	 * @return String representing vehicle state (online, asleep, offline, waking, unknown)
	 */
	private static String getVehicleState(String id) {
		int tries = 0;
		while(++tries < MAX_RETRIES) {
			JSONObject vehicleResponse = null;
			try {
				vehicleResponse = teslaAPI.requestJSON("api/1/vehicles/" + id);
			} catch (Exception ex) {
				String errorMessage = ExceptionUtils.getExceptionString(ex);
				if (errorMessage.indexOf("HTTP response code: 40") > 0) {
					refreshTokens();
				} else {
					logger.warn("Failed to get vehicle: {}", errorMessage);
				}
			}

			if (vehicleResponse != null) {
				JSONObject data = vehicleResponse.getJSONObject("response");
				logger.debug("Vehicle state response: {}", data.toString(2));
				if (data.has("state")) {
					return data.getString("state");
				}
			}

			sleep(RETRY_INTERVAL_SECONDS);
		}

		return "unknown";
	}

	/**
	 * Determine whether vehicle is currently charging.
	 * @param id ID of the vehicle to use when requesting the charge state
	 * @return Boolean for whether the vehicle is currently charging.
	 */
	private static boolean isVehicleCharging(String id) {
		JSONObject chargeState = getVehicleChargeState(id);
		if (chargeState != null) {
			try {
				if (chargeState.has("charging_state") && chargeState.has("charge_port_door_open")) {
					String chargingState = chargeState.getString("charging_state");
					return chargingState.toLowerCase().equals("charging");
				}
			} catch (Exception ex) { }
		}

		return false;
	}

	/**
	 * Load configuration into the variables we use. Also handles reloading configuration when the properties file is changed.
	 */
	private static void loadConfiguration() {
		File config = new File(propertiesFile);

		boolean configurationUpdated = false;
		if (lastConfigurationModification > 0 && config.lastModified() > lastConfigurationModification) {
			configurationUpdated = true;
			log("Configuration has changed");
		} else if (lastConfigurationModification > 0) {
			return;
		}
		lastConfigurationModification = config.lastModified();

		Properties prop = new Properties();
		try (FileInputStream fis = new FileInputStream(propertiesFile)) {
		    prop.load(fis);
		} catch (FileNotFoundException ex) {
			exitWithError(propertiesFile + " not found in current directory.");
		} catch (IOException ex) {
			exitWithError(propertiesFile + " could not be read.");
		}

		// Only read tokens and VIN on initial load, in case a different change has triggered a
		// refresh and somehow the tokens in the file are not the most current. If the VIN changes,
		// you're dealing with a whole new car so you should restart anyway.
		if (!configurationUpdated) {
			try {
				accessToken = prop.getProperty(ACCESS_TOKEN);
				refreshToken = prop.getProperty(REFRESH_TOKEN);
			} catch (Exception ex) { }

			if (accessToken == null || accessToken.length() == 0 || refreshToken == null || refreshToken.length() == 0) {
				exitWithError("Tesla API access token and/or refresh token missing.");
			}

			vin = prop.getProperty(VIN);
		}

		// Home lat/long
		try {
			double newHomeLatitude = Double.parseDouble(prop.getProperty(HOME_LATITUDE));
			double newHomeLongitude = Double.parseDouble(prop.getProperty(HOME_LONGITUDE));
			if (configurationUpdated && (newHomeLatitude != homeLatitude || newHomeLongitude != homeLongitude)) {
				log("New home lat/long: " + newHomeLatitude + "/" + newHomeLongitude);
			}
			homeLatitude = newHomeLatitude;
			homeLongitude = newHomeLongitude;
		} catch (Exception ex) {
			exitWithError("Exception while setting Home latitude/longitude: " + ExceptionUtils.getExceptionString(ex));
		}

		// Max electricity price
		try {
			double newMaxElectricityPrice = Double.parseDouble(prop.getProperty(MAX_ELECTRICITY_PRICE));
			if (configurationUpdated && newMaxElectricityPrice != maxElectricityPrice) {
				log("New max electricity price: " + newMaxElectricityPrice + "\u00A2 / kWh");
			}
			maxElectricityPrice = newMaxElectricityPrice;
		} catch (Exception ex) {
			maxElectricityPrice = 6;
		}

		// Polling interval
		try {
			int newPollIntervalSeconds = Integer.parseInt(prop.getProperty(POLL_INTERVAL_SECONDS));
			if (configurationUpdated && newPollIntervalSeconds != pollIntervalSeconds) {
				log("New polling interval: " + newPollIntervalSeconds + " seconds");
			}
			pollIntervalSeconds = newPollIntervalSeconds;
		} catch (Exception ex) {
			pollIntervalSeconds = 30;
		}

		// Departure charge settings
		boolean departureChargeUpdated = false;
		try {
			int newMinimumDepartureSoC = Integer.parseInt(prop.getProperty(MINIMUM_DEPARTURE_SOC));
			if (configurationUpdated && newMinimumDepartureSoC != minimumDepartureSoC) {
				log("New minimum departure SoC: " + newMinimumDepartureSoC + "%");
				departureChargeUpdated = true;
			}
			minimumDepartureSoC = newMinimumDepartureSoC;
		} catch (Exception ex) {
			minimumDepartureSoC = 0;
		}

		try {
			double newSoCGainPerHour = Double.parseDouble(prop.getProperty(SOC_GAIN_PER_HOUR));
			if (configurationUpdated && newSoCGainPerHour != soCGainPerHour) {
				log("New SoC gain per hour: " + newSoCGainPerHour + "%");
				departureChargeUpdated = true;
			}
			soCGainPerHour = newSoCGainPerHour;
		} catch (Exception ex) {
			soCGainPerHour = 0;
			minimumDepartureSoC = 0;
		}

		shouldChargeForDepature = (minimumDepartureSoC > 0 && soCGainPerHour > 0);
		if (configurationUpdated && departureChargeUpdated) {
			log("Vehicle will" + (!shouldChargeForDepature ? " not" : "") + " be charged to reach minimum departure SoC" + (minimumDepartureSoC > 0 ? " of " + minimumDepartureSoC + "%" : "") + ".");
		}

		// Restart on current drop setting
		boolean newRestartOnCurrentDrop = false;
		String restartOnCurrentDropSetting = prop.getProperty(RESTART_ON_CURRENT_DROP);
		if (restartOnCurrentDropSetting != null && restartOnCurrentDropSetting.toLowerCase().equals("y")) {
			newRestartOnCurrentDrop = true;
		}

		if (configurationUpdated && newRestartOnCurrentDrop != restartOnCurrentDrop) {
			log("New restart on current drop flag: " + (newRestartOnCurrentDrop ? "Y" : "N"));
		}
		restartOnCurrentDrop = newRestartOnCurrentDrop;
	}

	/**
	 * Log an informational message
	 * @param msg String message to log
	 */
	private static void log(String msg) {
		logger.info(msg);
	}

	/**
	 * Refresh the access and refresh tokens for Tesla API access
	 */
	private static void refreshTokens() {
		JSONObject body = new JSONObject()
			.put("grant_type", "refresh_token")
			.put("client_id", "ownerapi")
			.put("refresh_token", refreshToken)
			.put("scope", "openid email offline_access")
		;

		WebRequest tokenRequest = new WebRequest();
		try {
			String response = tokenRequest.post("https://auth.tesla.com/oauth2/v3/token", body.toString());
			JSONObject responseJSON = new JSONObject(response);
			logger.debug("Refresh tokens response: {}",  responseJSON.toString(2));
			String newAccessToken = null, newRefreshToken = null;
			if (responseJSON.has("refresh_token") && responseJSON.has("access_token")) {
				newAccessToken = responseJSON.getString("access_token");
				newRefreshToken = responseJSON.getString("refresh_token");
			}

			if (newAccessToken != null) {
				HashMap<String, Object> newTokens = new HashMap<String, Object>();
				newTokens.put(ACCESS_TOKEN, newAccessToken);
				newTokens.put(REFRESH_TOKEN, newRefreshToken);

				accessToken = newAccessToken;
				refreshToken = newRefreshToken;

				updateConfiguration(newTokens);
			} else {
				logger.error("Exception while handling new access token for Tesla Owner API: Null new access token");
			}
		} catch (Exception ex) {
			logger.error("Exception while getting refresh token for Tesla API: {}", ExceptionUtils.getExceptionString(ex));
		}

		setupAPIObjects();
	}

	/**
	 * Send a charge command to the vehicle
	 * @param id ID of the vehicle to use in the charge command request
	 * @param chargeCommand Command to send (start/stop)
	 * @return Boolean for whether the command was successful
	 */
	private static boolean sendChargeCommand(String id, String chargeCommand) {
		int tries = 0;
		String apiEndpoint = apiBase + "/api/1/vehicles/" + id + "/command/charge_" + chargeCommand;
		while(++tries < MAX_RETRIES) {
			try {
				String chargeResponse = teslaCommands.post(apiEndpoint);
				JSONObject responseJSON = new JSONObject(chargeResponse);
				if (responseJSON != null && responseJSON.has("response") && responseJSON.getJSONObject("response").has("result") &&
					responseJSON.getJSONObject("response").getBoolean("result") == true) {
					return true;
				}
			} catch (Exception ex) {
				String errorMessage = ExceptionUtils.getExceptionString(ex);
				if (errorMessage.indexOf("HTTP response code: 40") < 0) {
					logger.error("Exception while calling charging request to {}: {}", apiEndpoint, ExceptionUtils.getExceptionString(ex));
				} else {
					refreshTokens();
				}
			}

			sleep(RETRY_INTERVAL_SECONDS);
		}

		return false;
	}

	/**
	 * Create the objects we use to access the Tesla API.
	 */
	private static void setupAPIObjects() {
		teslaAPI = new RESTRequest().setBaseUrl(apiBase);
		teslaAPI.setBearer(accessToken);

		teslaCommands = new WebRequest();
		teslaCommands.setBearer(accessToken);
	}

	/**
	 * Pause execution for a provided number of seconds.
	 * @param seconds Number of seconds to pause execution
	 */
	private static void sleep(int seconds) {
		try {
			Thread.sleep(seconds * 1000);
		} catch (Exception e) { }
	}

	/**
	 * Start charging the vehicle
	 * @param id ID of the vehicle to use in the request to start charging
	 * @return Boolean for resulting charge state (true = charging, false = not charging)
	 */
	private static boolean startCharging(String id) {
		boolean result = sendChargeCommand(id, "start");
		log("Vehicle charge start " + (result ? "successful" : "failed") + ".");
		return result;
	}

	/**
	 * Stop charging the vehicle
	 * @param id ID of the vehicle to use in the request to stop charging
	 * @return Boolean for resulting charge state (true = charging, false = not charging)
	 */
	private static boolean stopCharging(String id) {
		boolean result = sendChargeCommand(id, "stop");
		log("Vehicle charge stop " + (result ? "successful" : "failed") + ".");
		return !result;
	}

	/**
	 * Store any updated configuration values in the properties file
	 * @param configChanges HashMap of configuration keys and values to update
	 */
	private static void updateConfiguration(HashMap<String, Object> configChanges) {
		logger.debug("Update configuration with {}", new JSONObject(configChanges).toString());		
		ArrayList<String> newLines = new ArrayList<String>();
		Scanner scanner;
		try {
			scanner = new Scanner(new File(propertiesFile));
			while(scanner.hasNextLine()) {
				String line = scanner.nextLine().trim();
				if (line.indexOf("=") > 0) {
					String[] parts = line.split("=", 2);
					String key = parts[0];
					String value = parts[1];
					if (configChanges.containsKey(key)) {
						value = configChanges.get(key).toString();
					}
					line = key + "=" + value;
				}
				newLines.add(line);
			}
			scanner.close();
		} catch (Exception ex) {
			logger.error("Configuration read/update exception: {}", ExceptionUtils.getExceptionString(ex));
		}

		if (newLines.size() > 0) {
			BufferedWriter writer;
			try {
				writer = new BufferedWriter(new FileWriter(propertiesFile));
				for(String line : newLines) {
					writer.write(line + "\n");
				}
				writer.close();

				sleep(1);
				File config = new File(propertiesFile);
				lastConfigurationModification = config.lastModified();
			} catch (Exception ex) {
				logger.error("Configuration write exception: {}", ExceptionUtils.getExceptionString(ex));
			}
		}
	}

	/**
	 * Request current location details from vehicle and store them in our queue, returning the current location details.
	 * @param id ID of the vehicle to use in the drive state request
	 * @return VehicleLocation object with location details returned from the Tesla API
	 */
	private static VehicleLocation updateVehicleLocationDetails(String id) {
		wakeUpVehicle(id);

		JSONObject driveStateResponse = getVehicleDriveState(id);
		VehicleLocation v = null;
		if (driveStateResponse != null) {
			try {
				if (driveStateResponse.has("latitude") && driveStateResponse.has("longitude")) {

					double currentLatitude = driveStateResponse.getDouble("latitude");
					double currentLongitude = driveStateResponse.getDouble("longitude");
					double speed = driveStateResponse.isNull("speed") ? 0 : driveStateResponse.getDouble("speed");
					double heading = driveStateResponse.getDouble("heading");
					double timestamp = driveStateResponse.getDouble("timestamp");

					v = new VehicleLocation(currentLatitude, currentLongitude, speed, heading, timestamp);

					// Vehicle is currently navigating. We can use that to schedule the next location poll!
					if (driveStateResponse.has("active_route_latitude") && driveStateResponse.has("active_route_longitude")) {
						double destinationLatitude = driveStateResponse.getDouble("active_route_latitude");
						double destinationLongitude = driveStateResponse.getDouble("active_route_longitude");

						VehicleLocation destination = new VehicleLocation(destinationLatitude, destinationLongitude);
						if (driveStateResponse.has("active_route_minutes_to_arrival")) {
							double destinationMinutesToArrival = driveStateResponse.getDouble("active_route_minutes_to_arrival");
							if (destination.distanceFrom(homeLatitude, homeLongitude) == 0) {
								v.setGoingHome(true);
							} else {
								v.setGoingAwayFromHome(true);
							}
							v.setMinutesToArrival(destinationMinutesToArrival);
						}
					}

					logger.debug("Most recent vehicle location: {}", v.toString());

					// When we see vehicle is at home, clear location history so when we start looking at history to
					// determine next polling times we don't have to worry about home -> destination -> home.
					double distanceFromHome = v.distanceFrom(homeLatitude, homeLongitude);
					if (distanceFromHome == 0) {
						vehicleLocationHistory.clear();
						lastHome = v;
					}

					// Only add this new location to the queue if it significantly different from the two entries that came
					// before it. We only need two entries in a row to decide the vehicle has stopped, no point in continuing
					// to fill the queue with stopped entries.
					int locationHistorySize = vehicleLocationHistory.size();
					if (locationHistorySize >= 2) {
						VehicleLocation mostRecentLocation = vehicleLocationHistory.get(locationHistorySize - 1);
						VehicleLocation previousLocation = vehicleLocationHistory.get(locationHistorySize - 2);
						if (!(mostRecentLocation.distanceFrom(previousLocation) == 0 && mostRecentLocation.distanceFrom(v) == 0)) {
							vehicleLocationHistory.add(v);
						}
					} else {
						vehicleLocationHistory.add(v);
					}
				}
			} catch (Exception ex) {
				logger.error("Failed to parse drive state response: {}", ExceptionUtils.getExceptionString(ex));
			}
		}

		return v;
	}

	/**
	 * Wake up the vehicle so we know future commands will work.
	 * @param id ID of the vehicle to use in the wake_up request
	 */
	private static void wakeUpVehicle(String id) {
		logger.debug("Wake up, Tesla {}!", id);
		boolean isAwake = false;
		int tries = 0;
		while(!isAwake && ++tries < MAX_RETRIES) {
			try {
				String wakeResponse = teslaCommands.post(apiBase + "/api/1/vehicles/" + id + "/wake_up");
				if (wakeResponse != null && wakeResponse.startsWith("{")) {
					JSONObject responseJSON = new JSONObject(wakeResponse);
					if (responseJSON != null && responseJSON.has("response")) {
						JSONObject response = responseJSON.getJSONObject("response");
						if (response.has("state") && response.getString("state").equals("online")) {
							isAwake = true;
						}
					} else {
						refreshTokens();
					}
				} else {
					refreshTokens();
				}
			} catch (Exception ex) {
				refreshTokens();
			}

			if (!isAwake) {
				sleep(RETRY_INTERVAL_SECONDS);
			}
		}
	}
}
