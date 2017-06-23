package rhsm.cli.tests;

import java.util.ArrayList;
import java.util.List;

import com.github.redhatqe.polarize.metadata.DefTypes.Project;
import com.github.redhatqe.polarize.metadata.TestDefinition;
import org.json.JSONException;
import org.json.JSONObject;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.redhat.qe.Assert;
import com.redhat.qe.tools.RemoteFileTasks;
import com.redhat.qe.tools.SSHCommandResult;

import rhsm.base.SubscriptionManagerCLITestScript;
import rhsm.cli.tasks.CandlepinTasks;

/**
 * @author skallesh
 * @throws Exception
 * @throws JSONException
 */
@Test(groups = { "AutoAttachDisabledByOwnerTests", "Tier3Tests" })
public class AutoAttachDisabledByOwnerTests extends SubscriptionManagerCLITestScript {
	List<String> ownerKey = new ArrayList<String>();
	String owner = null;

	@TestDefinition( projectID = {Project.RHEL6}
	               , testCaseID = {"RHEL6-39111"})
	@Test(description = "Disable Auto attach by Owner", groups = { "DisableOwner",
			"blockedByBug-1382355" }, enabled = true)
	public void DisableAutoAttachByOwner() throws Exception {
		JSONObject jsonData = new JSONObject();
		String resourcePath = "/owners/" + owner;
		jsonData.put("autobindDisabled", "true");
		CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl,
				resourcePath, jsonData);
		/*
		 * verify register with auto-attach works on a owner with
		 * autobindDisabled=false
		 */
		SSHCommandResult result = clienttasks.register(sm_clientUsername, sm_clientPassword, owner, null, null, null,
				null, true, null, null, (String) null, null, null, null, true, null, null, null, null, null);
		String expected = "Ignoring request to auto-attach. It is disabled for org '" + owner + "'.";
		Assert.assertTrue(result.getStderr().trim().equals(expected),
				"indicationg autobindDisabled is set true on owner");
		SSHCommandResult consumedSubscriptions = clienttasks.list(null, null, true, null, null, null, null, null, null,
				null, null, null, null, null);
		Assert.assertTrue(consumedSubscriptions.getStdout().trim().equals("No consumed subscription pools to list"));

		/* verify if healing works on a owner with autobindDisabled=false */
		clienttasks.autoheal(null, true, null, null, null, null, null);
		String logMessage = "Ignoring request to auto-attach. It is disabled for org '" + owner + "'.";
		String logMarker = System.currentTimeMillis()
				+ " disable auto-attach by Owner test , healing section ****************************************";
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, logMarker);
		clienttasks.run_rhsmcertd_worker(true);
		String rhsmLogTail = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, logMarker,
				"Ignoring request to auto-attach.");
		Assert.assertTrue(rhsmLogTail.contains(logMessage),
				"indicationg auto-attach is disabled at owner level,so healing will not update the certificates ");
		/*
		 * verify the log message when healing is disabled on a owner with
		 * auto-attach disabled
		 */
		clienttasks.autoheal(null, null, true, null, null, null, null);
		logMarker = System.currentTimeMillis()
				+ " disable autoheal on a Owner with auto-attach disabled ****************************************";
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, logMarker);
		logMessage = "Auto-heal disabled on server, skipping";
		clienttasks.run_rhsmcertd_worker(true);
		rhsmLogTail = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, logMarker,
				"Auto-heal disabled on server, skipping");
		Assert.assertTrue(rhsmLogTail.contains(logMessage), "indicating auto-heal is disabled");

		/*
		 * verify if subscription-manager auto-attach works on a owner with
		 * auto-attach disabled
		 */
		result = clienttasks.subscribe_(true, null, (String) null, null, null, null, null, null, null, null, null,
				null, null);
		expected = "Ignoring request to auto-attach. It is disabled for org '" + owner + "'.";
		Assert.assertTrue(result.getStderr().trim().equals(expected), "indicating auto-attach is disabled for owner");
		consumedSubscriptions = clienttasks.list(null, null, true, null, null, null, null, null, null, null, null, null,
				null, null);
		Assert.assertTrue(consumedSubscriptions.getStdout().trim().equals("No consumed subscription pools to list"));

		/*
		 * verify when disableAutobind is set to false , register with
		 * auto-attach works
		 */
		clienttasks.unregister(null, null, null, null);
		jsonData.put("autobindDisabled", "false");
		CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl,
				resourcePath, jsonData);
		result = clienttasks.register(sm_clientUsername, sm_clientPassword, owner, null, null, null, null, true, null,
				null, (String) null, null, null, null, true, null, null, null, null, null);
		expected = "Ignoring request to auto-attach. It is disabled for org '" + owner + "'.";
		Assert.assertFalse(result.getStderr().trim().equals(expected),
				"indicationg autobindDisabled is set false on owner");
		consumedSubscriptions = clienttasks.list(null, null, true, null, null, null, null, null, null, null, null, null,
				null, null);
		Assert.assertFalse(consumedSubscriptions.getStdout().trim().equals("No consumed subscription pools to list"));

		/*
		 * verify when disableAutobind is set to false , subscription-manager
		 * auto-attach works
		 */
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		result = clienttasks.subscribe(true, null, (String) null, null, null, null, null, null, null, null, null, null, null);
		expected = "Ignoring request to auto-attach. It is disabled for org '" + owner + "'.";
		Assert.assertFalse(result.getStderr().trim().equals(expected), "indicating auto-attach is disabled for owner");
		consumedSubscriptions = clienttasks.list(null, null, true, null, null, null, null, null, null, null, null, null,
				null, null);
		Assert.assertFalse(consumedSubscriptions.getStdout().trim().equals("No consumed subscription pools to list"));
		/*
		 * verify when disableAutobind is set to false , auto-heal works
		 */
		clienttasks.unsubscribeFromAllOfTheCurrentlyConsumedProductSubscriptions();
		clienttasks.autoheal(null, true, null, null, null, null, null);
		logMessage = "Ignoring request to auto-attach. It is disabled for org '" + owner + "'.";
		logMarker = System.currentTimeMillis()
				+ " disable auto-attach by Owner test , healing section ****************************************";
		RemoteFileTasks.markFile(client, clienttasks.rhsmLogFile, logMarker);
		clienttasks.run_rhsmcertd_worker(true);
		rhsmLogTail = RemoteFileTasks.getTailFromMarkedFile(client, clienttasks.rhsmLogFile, logMarker,
				"Ignoring request to auto-attach. It is disabled for org ");
		Assert.assertFalse(rhsmLogTail.contains(logMessage),
				"indicationg auto-attach is enabled at owner level,so healing will update the certs ");
		// todo add test for activation key , after mstead confirms the exact
		// behaviour

	}

	@AfterGroups(groups = "setup", value = { "DisableOwner" }, enabled = true)
	public void setautobindDisabledToFalse() throws Exception {
		JSONObject jsonData = new JSONObject();
		String resourcePath = "/owners/" + owner;
		jsonData.put("autobindDisabled", "false");
		CandlepinTasks.putResourceUsingRESTfulAPI(sm_serverAdminUsername, sm_serverAdminPassword, sm_serverUrl,
				resourcePath, jsonData);
	}

	@BeforeClass(groups = { "setup" })
	public void getOwner() throws JSONException, Exception {
		ownerKey = CandlepinTasks.getOrgsKeyValueForUser(sm_clientUsername, sm_clientPassword, sm_serverUrl, "key");
		owner = ownerKey.get(randomGenerator.nextInt(ownerKey.size()));
	}

	@BeforeClass(groups = "setup")
	public void restoreRhsmProductCertDir() {
		String rhsmProductCertDir = clienttasks.productCertDir;
		System.out.println(clienttasks.productCertDir + ".....is the rpoduct DIR");
		if (clienttasks == null)
			return;
		if (!(rhsmProductCertDir == (clienttasks.getConfFileParameter(clienttasks.rhsmConfFile, "rhsm",
				"productCertDir"))))
			log.info("Restoring the originally configured product cert directory...");
		clienttasks.updateConfFileParameter(clienttasks.rhsmConfFile, "productCertDir", rhsmProductCertDir);
	}
}