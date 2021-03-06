package com.github.johnpaularthur.rundeck.plugins;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;

/**
 * Rundeck slack plugin class.
 *
 * @author Sylvain Bugat
 *
 */
@Plugin(service = ServiceNameConstants.Notification, name = "SlackNotificationPlugin")
@PluginDescription(title = "Slack")
public class SlackPlugin implements NotificationPlugin {

	static final String UTF_8 = "UTF-8";
	
	static final String SLACK_SUCCESS_COLOR = "good";
	static final String SLACK_FAILED_COLOR = "danger";

	private final Logger logger = Logger.getLogger(SlackPlugin.class.getName());

	@PluginProperty(title = "Incoming WebHook URL", description = "Slack incoming WebHook URL", required = true, scope = PropertyScope.Project)
	private String slackIncomingWebHookUrl;

	@PluginProperty(title = "WebHook channel", description = "Override default WebHook channel (#channel)", scope = PropertyScope.Instance)
	private String slackOverrideDefaultWebHookChannel;

	@PluginProperty(title = "WebHook name", description = "Override default WebHook name", scope = PropertyScope.Project)
	private String slackOverrideDefaultWebHookName;

	@PluginProperty(title = "WebHook emoji", description = "Override default WebHook icon (:emoji:)", scope = PropertyScope.Instance)
	private String slackOverrideDefaultWebHookEmoji;

    @PluginProperty(title = "Environment name", description = "Provides an environment name. e.g prod, qa, dev, etc", scope = PropertyScope.Project)
    private String rundeckServerEnvironmentName;

	private URLTools uRLTools = new URLTools();

	@Override
	public boolean postNotification(final String trigger, @SuppressWarnings("rawtypes") final Map executionData, @SuppressWarnings("rawtypes") final Map config) {

		@SuppressWarnings("unchecked")
		final Map<String, String> jobMap = (Map<String, String>) executionData.get("job");

		final String jobName;
		if (null != jobMap) {
			jobName = jobMap.get("name");
		} else {
			jobName = null;
		}

		logger.log(Level.FINE, "Start to send Slack notification to WebHook URL {0} for the job {1} with trigger {2}", new Object[] { slackIncomingWebHookUrl, jobName, trigger });

		HttpURLConnection connection = null;
		try {

			// Prepare the connection to Slack
			connection = uRLTools.openURLConnection(slackIncomingWebHookUrl);

			connection.setRequestMethod("POST");
			connection.setRequestProperty("charset", UTF_8);
			connection.setUseCaches(false);
			connection.setDoInput(true);
			connection.setDoOutput(true);

			// Send the WebHook message
			final String messagePayload = getMessage(trigger, executionData, rundeckServerEnvironmentName);
			DataOutputStream dataOutputStream = null;
			try {
				dataOutputStream = new DataOutputStream(connection.getOutputStream());
				dataOutputStream.writeBytes("payload=" + URLEncoder.encode(messagePayload, UTF_8));
			}
			finally {
				if (null != dataOutputStream) {
					dataOutputStream.close();
				}
			}

			// Get the HTTP response code
			final int httpResponseCode = connection.getResponseCode();
			if (HttpURLConnection.HTTP_OK != httpResponseCode) {

				if (HttpURLConnection.HTTP_NOT_FOUND == httpResponseCode) {
					logger.log(Level.SEVERE, "Invalid Slack WebHook URL {0} when sending {1} job notification with trigger {2}", new Object[] { slackIncomingWebHookUrl, jobName, trigger });
				} else {
					logger.log(Level.SEVERE, "Error sending {0} job notification with trigger {1}, http code: {2}", new Object[] { jobName, trigger, httpResponseCode });
					logger.log(Level.FINE, "Error sending {0} job notification with trigger {1}, http code: {2}, payload:{3}", new Object[] { jobName, trigger, httpResponseCode, messagePayload });
				}
				return false;
			}
		}catch (final MalformedURLException e) {
			logger.log(Level.SEVERE, "Malformed Slack WebHook URL {0} when sending {1} job notification with trigger {2}", new Object[] { slackIncomingWebHookUrl, jobName, trigger });
			return false;
		}catch (final IOException e) {
			logger.log(Level.SEVERE, e.getMessage());
			logger.log(Level.FINE, e.getMessage(), e);
			return false;
		}finally {
			if (null != connection) {
				connection.disconnect();
			}
		}

		return true;
	}
	
	/**
	 * 
	 * Return the complete message to send.
	 * 
	 * @param trigger job trigger state
	 * @param executionData data of the current execution state
	 * @return complete message
	 */
	private String getMessage( final String trigger, @SuppressWarnings("rawtypes") final Map executionData, final String envName) {
		final StringBuilder messageBuilder = new StringBuilder();
		messageBuilder.append('{');
		getOptions(messageBuilder);
		getAttachmentsPart(messageBuilder, trigger, executionData, envName);
		messageBuilder.append('}');
		
		return messageBuilder.toString();
	}

	/**
	 * Add optional channel, username and emoji to the message.
	 *
	 * @param messageBuilder StringBuilder to build the complete message
	 */
	private void getOptions(final StringBuilder messageBuilder) {

		if (null != slackOverrideDefaultWebHookChannel && !slackOverrideDefaultWebHookChannel.isEmpty()) {
			messageBuilder.append("\"channel\":");
			messageBuilder.append("\"" + slackOverrideDefaultWebHookChannel + "\",");
		}
		if (null != slackOverrideDefaultWebHookName && !slackOverrideDefaultWebHookName.isEmpty()) {
			messageBuilder.append("\"username\":");
			messageBuilder.append("\"" + slackOverrideDefaultWebHookName + "\",");
		}
		if (null != slackOverrideDefaultWebHookEmoji && !slackOverrideDefaultWebHookEmoji.isEmpty()) {
			messageBuilder.append("\"icon_emoji\":");
			messageBuilder.append("\"" + slackOverrideDefaultWebHookEmoji + "\",");
		}
	}

	/**
	 * Add job execution data to the message.
	 *
	 * @param messageBuilder StringBuilder to build the complete message
	 * @param trigger execution status
	 * @param executionData current execution state
	 */
	private static void getAttachmentsPart(final StringBuilder messageBuilder, final String trigger, @SuppressWarnings("rawtypes") final Map executionData, final String envName) {

		// Success and starting execution are good(green)
		final String statusColor;
		if ("success".equals(trigger) || "start".equals(trigger)) {
			statusColor = SLACK_SUCCESS_COLOR;
		} else {
			statusColor = SLACK_FAILED_COLOR;
		}

		// Attachment begin and title
		messageBuilder.append("\"attachments\":[");
		messageBuilder.append("{");
		messageBuilder.append("\"title\":\"");
		getTitlePart(messageBuilder, executionData);
		messageBuilder.append("\",");
		messageBuilder.append("\"text\":\"");
		getDurationPart(messageBuilder, executionData);
		getDownloadOptionPart(messageBuilder, executionData);
		messageBuilder.append("\",");
		messageBuilder.append("\"color\":\"" + statusColor + "\"");

		// Job options section
		messageBuilder.append(getJobOptionsPart(executionData));

		messageBuilder.append('}');

		// Succeeded nodes section
		messageBuilder.append(getSucceededNodesAttachment(executionData, statusColor, envName));
		// Failed nodes section
		messageBuilder.append(getFailedNodesAttachment(executionData, statusColor, envName));

		messageBuilder.append(']');
	}

	private static void getDownloadOptionPart(final StringBuilder downloadOptionBuilder, @SuppressWarnings("rawtypes") final Map executionData) {

		// Context map containing additional information
		@SuppressWarnings("unchecked")
		final Map<String, Map<String, String>> contextMap = (Map<String, Map<String, String>>) executionData.get("context");
		if (null == contextMap) {
			return;
		}

		final Map<String, String> jobContextMap = contextMap.get("job");

		// Download link if the job fails
		// example - http://john-arthur-mbp.local:4440/project/Warehouse/execution/renderOutput/436?ansicolor=on&loglevels=on
		boolean download = false;
		if (!"running".equals(executionData.get("status")) && !"success".equals(executionData.get("status"))) {
			downloadOptionBuilder.append("\n<" + jobContextMap.get("serverUrl") + "project/" + executionData.get("project") + "/execution/renderOutput/" + executionData.get("id") + "?ansicolor=on&loglevels=on|View log ouput>");
			download = true;
		}

		final Map<String, String> optionContextMap = contextMap.get("option");

		// Option header
		if (null != optionContextMap && !optionContextMap.isEmpty()) {
			if (!download) {
				downloadOptionBuilder.append("\nJob options:");
			} else {
				downloadOptionBuilder.append(", job options:");
			}
		}
	}

	private static void  getDurationPart(final StringBuilder durationBuilder, @SuppressWarnings("rawtypes") final Map executionData) {

		final Long startTime = (Long) executionData.get("dateStartedUnixtime");
		if (null == startTime) {
			return;
		}

		final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());

		durationBuilder.append("Launched by ");
		durationBuilder.append(executionData.get("user"));
		durationBuilder.append(" at ");
		durationBuilder.append(dateFormat.format(new Date(startTime.longValue())));

		if ("aborted".equals(executionData.get("status")) && null != executionData.get("abortedby")) {

			durationBuilder.append(executionData.get("status"));
			durationBuilder.append(" by ");
			durationBuilder.append(executionData.get("abortedby"));
		}
		if (!"running".equals(executionData.get("status"))) {

			if ("timedout".equals(executionData.get("status"))) {
				durationBuilder.append(", timed-out");
			} else {
				durationBuilder.append(", ended");
			}

			if (null != executionData.get("dateEndedUnixtime")) {
				final long endTime = ((Long) executionData.get("dateEndedUnixtime")).longValue();

				durationBuilder.append(" at ");
				durationBuilder.append(dateFormat.format(new Date(endTime)));
				durationBuilder.append(" (duration: ");
				durationBuilder.append(formatDuration(endTime - startTime));
				durationBuilder.append(')');
			}
		}
	}

	private static void getTitlePart(final StringBuilder titleBuilder, @SuppressWarnings("rawtypes") final Map executionData) {

		@SuppressWarnings("unchecked")
		final Map<String, String> jobMap = (Map<String, String>) executionData.get("job");
		if (null == jobMap) {
			return;
		}

		// Context map containing additional information
		@SuppressWarnings("unchecked")
		final Map<String, Map<String, String>> contextMap = (Map<String, Map<String, String>>) executionData.get("context");
		if (null == contextMap) {
			return;
		}

		final Map<String, String> jobContextMap = contextMap.get("job");
		if (null == jobContextMap) {
			return;
		}

		titleBuilder.append('<');
		titleBuilder.append(executionData.get("href"));
		titleBuilder.append("|#");
		titleBuilder.append(executionData.get("id"));
		titleBuilder.append(" - ");
		final String status;
		if (null != executionData.get("status")) {
			status = ((String) executionData.get("status")).toUpperCase();
		} else {
			status = null;
		}
		titleBuilder.append(status);

		if ("aborted".equals(executionData.get("status")) && null != executionData.get("abortedby")) {
			titleBuilder.append(" by ");
			titleBuilder.append(executionData.get("abortedby"));
		}

		titleBuilder.append(" - ");
		titleBuilder.append(jobMap.get("name"));
		titleBuilder.append("> - <");
		titleBuilder.append(jobContextMap.get("serverUrl"));
		titleBuilder.append("project/");
		titleBuilder.append(executionData.get("project"));
		titleBuilder.append("/jobs");
		titleBuilder.append('|');
		titleBuilder.append(executionData.get("project"));
		titleBuilder.append("> - ");

		if (null != jobMap.get("group") && !jobMap.get("group").isEmpty()) {

			final StringBuilder rootGroups = new StringBuilder();
			for (final String group : jobMap.get("group").split("/")) {

				rootGroups.append('/');
				rootGroups.append(group);

				titleBuilder.append('<');
				titleBuilder.append(jobContextMap.get("serverUrl"));
				titleBuilder.append("project/");
				titleBuilder.append(executionData.get("project"));
				titleBuilder.append("/jobs");
				titleBuilder.append(rootGroups);
				titleBuilder.append('|');
				titleBuilder.append(group);
				titleBuilder.append(">/");
			}
		}

		titleBuilder.append('<');
		titleBuilder.append(jobMap.get("href"));
		titleBuilder.append('|');
		titleBuilder.append(jobMap.get("name"));
		titleBuilder.append('>');
	}

	private static CharSequence getJobOptionsPart(@SuppressWarnings("rawtypes") final Map executionData) {

		final StringBuilder messageBuilder = new StringBuilder();

		// Context map containing additional information
		@SuppressWarnings("unchecked")
		final Map<String, Map<String, String>> contextMap = (Map<String, Map<String, String>>) executionData.get("context");

		if (null == contextMap) {
			return messageBuilder;
		}

		final Map<String, String> optionContextMap = contextMap.get("option");
		final Map<String, String> secureOptionContextMap = contextMap.get("secureOption");

		// Options part, secure options values are not displayed
		if (null != optionContextMap && !optionContextMap.isEmpty()) {

			messageBuilder.append(",\"fields\":[");
			boolean firstOption = true;
			for (final Map.Entry<String, String> mapEntry : optionContextMap.entrySet()) {

				if (!firstOption) {
					messageBuilder.append(',');
				}
				messageBuilder.append("{");
				messageBuilder.append("\"title\":\"" + mapEntry.getKey() + "\",");
				messageBuilder.append("\"value\":\"");

				if (null != secureOptionContextMap && null != secureOptionContextMap.get(mapEntry.getKey())) {
					messageBuilder.append("***********");
				} else {
					messageBuilder.append(mapEntry.getValue());
				}

				messageBuilder.append("\",");
				messageBuilder.append("\"short\":true");
				messageBuilder.append("}");

				firstOption = false;
			}

			messageBuilder.append("]");
		}

		return messageBuilder;
	}

	/**
	 * Construct the failed nodes section.
	 *
	 * @param executionData current execution state
	 * @param statusColor status color to display
	 * @return char sequence containing the formated section
	 */
	private static CharSequence getFailedNodesAttachment(@SuppressWarnings("rawtypes") final Map executionData, final String statusColor, final String envName) {

		final StringBuilder messageBuilder = new StringBuilder();

		@SuppressWarnings("unchecked")
		final List<String> failedNodeList = (List<String>) executionData.get("failedNodeList");
		@SuppressWarnings("unchecked")
		final Map<String, Integer> nodeStatus = (Map<String, Integer>) executionData.get("nodestatus");

		final int totalNodes;
		if (null != nodeStatus && null != nodeStatus.get("total")) {
			totalNodes = nodeStatus.get("total").intValue();
		} else {
			totalNodes = 0;
		}

		// Failed node part if a node is failed
		if (null != failedNodeList && !failedNodeList.isEmpty() && totalNodes > 0) {
			messageBuilder.append(",{");
			messageBuilder.append("\"fallback\":\"Failed nodes list\",");
			messageBuilder.append("\"text\":\"Failed nodes:\",");
			messageBuilder.append("\"color\":\"");
			messageBuilder.append(statusColor);
			messageBuilder.append("\",");
			messageBuilder.append("\"fields\":[");

			// Format a list with all failed nodes
			boolean firstNode = true;
			for (final String failedNode : failedNodeList) {

				if (!firstNode) {
					messageBuilder.append(',');
				}
				messageBuilder.append("{");

				messageBuilder.append("\"title\":\"");
                messageBuilder.append(failedNode);
                messageBuilder.append("(");
                messageBuilder.append(envName);
                messageBuilder.append(")");
				messageBuilder.append("\",");
				messageBuilder.append("\"short\":true");

				messageBuilder.append("}");

				firstNode = false;
			}

			messageBuilder.append(']');
			messageBuilder.append('}');
		}

		return messageBuilder;
	}

	/**
	 * Construct the failed nodes section.
	 *
	 * @param executionData current execution state
	 * @param statusColor status color to display
	 * @return char sequence containing the formated section
	 */
	private static CharSequence getSucceededNodesAttachment(@SuppressWarnings("rawtypes") final Map executionData, final String statusColor, final String envName) {

		final StringBuilder messageBuilder = new StringBuilder();

		@SuppressWarnings("unchecked")
		final List<String> succeededNodeList = (List<String>) executionData.get("succeededNodeList");
		@SuppressWarnings("unchecked")
		final Map<String, Integer> nodeStatus = (Map<String, Integer>) executionData.get("nodestatus");

		final int totalNodes;
		if (null != nodeStatus && null != nodeStatus.get("total")) {
			totalNodes = nodeStatus.get("total").intValue();
		} else {
			totalNodes = 0;
		}

		// Succeeded node part if a node is succeeded and if it's not the only one node executed
		if (null != succeededNodeList && !succeededNodeList.isEmpty() && totalNodes > 0) {
			messageBuilder.append(",{");
			messageBuilder.append("\"fallback\":\"Succeeded nodes list\",");
			messageBuilder.append("\"text\":\"Succeeded nodes:\",");
			messageBuilder.append("\"color\":\"");
			messageBuilder.append(statusColor);
			messageBuilder.append("\",");
			messageBuilder.append("\"fields\":[");

			// Format a list with all succeeded nodes
			boolean firstNode = true;
			for (final String succeededNode : succeededNodeList) {

				if (!firstNode) {
					messageBuilder.append(',');
				}
				messageBuilder.append("{");

				messageBuilder.append("\"title\":\"");
				messageBuilder.append(succeededNode);
                messageBuilder.append("(");
                messageBuilder.append(envName);
                messageBuilder.append(")");
				messageBuilder.append("\",");
				messageBuilder.append("\"short\":true");

				messageBuilder.append("}");

				firstNode = false;
			}

			messageBuilder.append(']');
			messageBuilder.append('}');
		}

		return messageBuilder;
	}

	/**
	 * Format a millisecond duration to a readeable formatted String.
	 *
	 * @param milliseconds a positive duration in milliseconds to convert
	 * @return A string of the form "XdYh" or "XhYm" or "XmYs" or "Xs".
	 */
	public static CharSequence formatDuration(final long milliseconds) {

		long millisecondsReminder = milliseconds;

		final long days = TimeUnit.MILLISECONDS.toDays(millisecondsReminder);

		if (days > 0) {
			millisecondsReminder -= TimeUnit.DAYS.toMillis(days);
			final long hours = TimeUnit.MILLISECONDS.toHours(millisecondsReminder);
			return String.format("%dd%02dh", Long.valueOf(days), Long.valueOf(hours));
		}

		final long hours = TimeUnit.MILLISECONDS.toHours(millisecondsReminder);
		if (hours > 0) {
			millisecondsReminder -= TimeUnit.HOURS.toMillis(hours);
			final long minutes = TimeUnit.MILLISECONDS.toMinutes(millisecondsReminder);
			return String.format("%dh%02dm", Long.valueOf(hours), Long.valueOf(minutes));
		}

		final long minutes = TimeUnit.MILLISECONDS.toMinutes(millisecondsReminder);
		if (minutes > 0) {
			millisecondsReminder -= TimeUnit.MINUTES.toMillis(minutes);
			final Long seconds = Long.valueOf(TimeUnit.MILLISECONDS.toSeconds(millisecondsReminder));

			return String.format("%dm%02ds", Long.valueOf(minutes), seconds);
		}

		final Long seconds = Long.valueOf(TimeUnit.MILLISECONDS.toSeconds(millisecondsReminder));
		return String.format("%ds", seconds);
	}
}
