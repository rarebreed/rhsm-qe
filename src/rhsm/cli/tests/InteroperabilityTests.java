package rhsm.cli.tests;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONException;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.redhat.qe.Assert;
import com.redhat.qe.auto.tcms.ImplementsNitrateTest;
import com.redhat.qe.jul.TestRecords;
import rhsm.base.SubscriptionManagerCLITestScript;
import rhsm.data.EntitlementCert;
import rhsm.data.ProductNamespace;
import rhsm.data.SubscriptionPool;
import com.redhat.qe.tools.RemoteFileTasks;
import com.redhat.qe.tools.SSHCommandResult;

/**
 * @author jsefler
 *
 *
 */
@Test(groups={"InteroperabilityTests","Tier2Tests"})
public class InteroperabilityTests extends SubscriptionManagerCLITestScript {

	// Test methods ***********************************************************************
	
	@Test(	description="User is warned when already registered using RHN Classic",
			groups={"InteroperabilityRegister_Test", "AcceptanceTests","Tier1Tests", "blockedByBug-730018", "blockedByBug-755130", "blockedByBug-847795", "blockedByBug-859090", "blockedByBug-877590"},
			enabled=true)
	@ImplementsNitrateTest(caseId=75972)	
	public void InteroperabilityRegister_Test() {
		SSHCommandResult result;
		
		// interoperabilityWarningMessage is defined in /usr/share/rhsm/subscription_manager/branding/__init__.py self.REGISTERED_TO_OTHER_WARNING
		String interoperabilityWarningMessage = clienttasks.msg_InteroperabilityWarning;
		
		// query the branding python file directly to get the default interoperabilityWarningMessage (when the subscription-manager rpm came from a git build - this assumes that any build of subscription-manager must have a branding module e.g. redhat_branding.py)
		/* TEMPORARILY COMMENTING OUT SINCE JBOWES IS INCLUDING THIS BRANDING FILE IN THE PUBLIC REPO - jsefler 9/15/2011
		if (client.runCommandAndWait("rpm -q subscription-manager").getStdout().contains(".git.")) {
			interoperabilityWarningMessage = clienttasks.getBrandingString("REGISTERED_TO_OTHER_WARNING");
		}
		*/
		String interoperabilityWarningMessageRegex = "^"+interoperabilityWarningMessage.replaceAll("\\(", "\\\\(").replaceAll("\\)", "\\\\)").replaceAll("\\.", "\\\\.");
		Assert.assertTrue(interoperabilityWarningMessage.startsWith("WARNING"), "The expected interoperability message starts with \"WARNING\".");
		
		if (!isRhnClientToolsInstalled) {
			log.warning("Skipping some RHN Classic interoperability test assertions when the '"+rhnClientTools+"' package is not installed.");
		} else {
			
			log.info("Simulating registration to RHN Classic by creating an empty systemid file '"+clienttasks.rhnSystemIdFile+"'...");
			RemoteFileTasks.runCommandAndWait(client, "touch "+clienttasks.rhnSystemIdFile, TestRecords.action());
			Assert.assertTrue(RemoteFileTasks.testExists(client, clienttasks.rhnSystemIdFile), "RHN Classic systemid file '"+clienttasks.rhnSystemIdFile+"' is in place.");
			
			log.info("Attempt to register while already registered via RHN Classic...");
			result = clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, true, false, null, null, null);
			//Assert.assertTrue(result.getStdout().startsWith(interoperabilityWarningMessage), "subscription-manager warns the registerer when the system is already registered via RHN Classic with this expected message:\n"+interoperabilityWarningMessage);
			//Assert.assertContainsMatch(result.getStdout(),interoperabilityWarningMessageRegex, "subscription-manager warns the registerer when the system is already registered via RHN Classic with the expected message.");
			Assert.assertTrue(result.getStdout().contains(interoperabilityWarningMessage), "subscription-manager warns the registerer when the system is already registered via RHN Classic with this expected message:\n"+interoperabilityWarningMessage+"\n");
		}
		
		log.info("Now let's make sure we are NOT warned when we are NOT already registered via RHN Classic...");
		clienttasks.removeRhnSystemIdFile();
		Assert.assertTrue(!RemoteFileTasks.testExists(client, clienttasks.rhnSystemIdFile), "RHN Classic systemid file '"+clienttasks.rhnSystemIdFile+"' is gone.");
		result = clienttasks.register(sm_clientUsername, sm_clientPassword, sm_clientOrg, null, null, null, null, null, null, null, (String)null, null, null, null, true, false, null, null, null);
		
		//Assert.assertFalse(result.getStdout().startsWith(interoperabilityWarningMessage), "subscription-manager does NOT warn registerer when the system is not already registered via RHN Classic.");
		//Assert.assertContainsNoMatch(result.getStdout(),interoperabilityWarningMessageRegex, "subscription-manager does NOT warn registerer when the system is NOT already registered via RHN Classic.");
		Assert.assertTrue(!result.getStdout().contains(interoperabilityWarningMessage), "subscription-manager does NOT warn registerer when the system is NOT already registered via RHN Classic.");
	}
	
	
	@Test(	description="When registered to RHSM (and all subscriptions have expired), the subscription-manager yum plugin should inform that: The subscription for following product(s) has expired: etc.",
			groups={"YumPluginMessageCase_Tests","blockedByBug-818383","blockedByBug-832119","blockedByBug-871146", "blockedbyBug-901612", "blockedbyBug-1017354","blockedByBug-1087620","blockedByBug-1058380","blockedByBug-1122772"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)	
	public void YumPluginMessageCase0_Test() throws JSONException, Exception {
		clienttasks.register(sm_clientUsername,sm_clientPassword,sm_clientOrg,null,null,null,null,null,null,null,(List<String>)null,null,null,null,true,false,null,null, null);
		
		// import an expired certificate 
		File expiredCertFile = new File(System.getProperty("automation.dir", null)+"/certs/Expiredcert.pem");
		RemoteFileTasks.putFile(client.getConnection(), expiredCertFile.getPath(), "/tmp/Expiredcert.pem", "0644");
		clienttasks.importCertificate("/tmp/Expiredcert.pem");
		EntitlementCert expiredEntitlementCert = clienttasks.getEntitlementCertFromEntitlementCertFile(new File("/tmp/Expiredcert.pem"));
		
		// assert the registration message (without any current subscriptions)
		SSHCommandResult result = client.runCommandAndWait("yum repolist --disableplugin=rhnplugin --enableplugin=subscription-manager");
		String expectedMsgRHSM = "This system is registered to Red Hat Subscription Management, but is not receiving updates. You can use subscription-manager to assign subscriptions.";
		if (clienttasks.isPackageVersion("subscription-manager", "<", "1.12.2-1")) Assert.assertTrue(result.getStdout().contains(expectedMsgRHSM), "When registered to RHSM (and all subscriptions have expired), the subscription-manager yum plugin stdout should inform that:\n"+expectedMsgRHSM+"\n");	// Bug 901612 - Subscription-manager-s yum plugin prints warning to stdout instead of stderr.	// Bug 901612 was reverted by Bug 1017354 
		else if (clienttasks.isPackageVersion("subscription-manager", ">=", "1.12.11-1")) Assert.assertTrue(result.getStdout().contains(expectedMsgRHSM), "When registered to RHSM (and all subscriptions have expired), the subscription-manager yum plugin stdout should inform that:\n"+expectedMsgRHSM+"\n");	// Bug 1058380 was reverted by Bug 1122772 
		else Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to RHSM (and all subscriptions have expired), the subscription-manager yum plugin stdout should NO LONGER inform that:\n"+expectedMsgRHSM+"\nBugzilla https://bugzilla.redhat.com/show_bug.cgi?id=1058380 was used to remove this usability messaging implemented for https://bugzilla.redhat.com/show_bug.cgi?id=818383");
		// assert the expired subscriptions message
		expectedMsgRHSM = "*** WARNING ***\nThe subscription for following product(s) has expired:";
		for (ProductNamespace productNamespace : expiredEntitlementCert.productNamespaces) expectedMsgRHSM += "\n"+"  - "+productNamespace.name;
		expectedMsgRHSM += "\n"+"You no longer have access to the repositories that provide these products.  It is important that you apply an active subscription in order to resume access to security and other critical updates. If you don't have other active subscriptions, you can renew the expired subscription.";
		Assert.assertTrue(result.getStdout().contains(expectedMsgRHSM), "When registered to RHSM (and all subscriptions have expired), the subscription-manager yum plugin stdout should inform that:\n"+expectedMsgRHSM+"\n");	// Bug 901612 - Subscription-manager-s yum plugin prints warning to stdout instead of stderr.	// Bug 901612 was reverted by Bug 1017354 

		// assert the registration message (with current subscriptions)
		//clienttasks.subscribeToSubscriptionPool(clienttasks.getCurrentlyAvailableSubscriptionPools().get(0));	// will fail with java.lang.AssertionError: The list of consumed products is entitled 'Consumed Subscriptions'. expected:<true> but was:<false>
		clienttasks.subscribe(null, null, clienttasks.getCurrentlyAvailableSubscriptionPools().get(0).poolId, null, null, null, null, null, null, null, null, null);
		clienttasks.importCertificate("/tmp/Expiredcert.pem");
		result = client.runCommandAndWait("yum repolist --disableplugin=rhnplugin --enableplugin=subscription-manager");
		expectedMsgRHSM = "This system is receiving updates from Red Hat Subscription Management.";
		//NOT TRUE ANYMORE Assert.assertTrue(result.getStdout().contains(expectedMsgRHSM), "When registered to RHSM (and some subscriptions have expired), the subscription-manager yum plugin stdout should inform that:\n"+expectedMsgRHSM+"\n");	// Bug 901612 - Subscription-manager-s yum plugin prints warning to stdout instead of stderr.	// Bug 901612 was reverted by Bug 1017354 
		Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to RHSM, the subscription-manager yum plugin NO LONGER informs that:\n"+expectedMsgRHSM+"\nFor justification, see https://bugzilla.redhat.com/show_bug.cgi?id=1017354#c12");	// commit 39eadae14eead4bb79978e52d38da2b3e85cba57 1017354: remove msg printed to stderr via yum

		// assert the expired subscriptions message again
		expectedMsgRHSM = "*** WARNING ***\nThe subscription for following product(s) has expired:";
		for (ProductNamespace productNamespace : expiredEntitlementCert.productNamespaces) expectedMsgRHSM += "\n"+"  - "+productNamespace.name;
		expectedMsgRHSM += "\n"+"You no longer have access to the repositories that provide these products.  It is important that you apply an active subscription in order to resume access to security and other critical updates. If you don't have other active subscriptions, you can renew the expired subscription.";
		Assert.assertTrue(result.getStdout().contains(expectedMsgRHSM), "When registered to RHSM (and some subscriptions have expired), the subscription-manager yum plugin stdout should inform that:\n"+expectedMsgRHSM+"\n");	// Bug 901612 - Subscription-manager-s yum plugin prints warning to stdout instead of stderr.	// Bug 901612 was reverted by Bug 1017354 
	}
	
	@Test(	description="When not registered to either RHN nor RHSM, the subscription-manager yum plugin should inform that: This system is not registered to Red Hat Subscription Management. You can use subscription-manager to register.",
			groups={"YumPluginMessageCase_Tests","blockedByBug-818383","blockedByBug-832119","blockedByBug-830193","blockedByBug-830194","blockedByBug-906875","blockedByBug-1058380","blockedByBug-1122772"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)	
	public void YumPluginMessageCase1_Test() {
		clienttasks.unregister(null,null,null);
		clienttasks.removeRhnSystemIdFile();
		SSHCommandResult result = client.runCommandAndWait("yum repolist --enableplugin=rhnplugin --enableplugin=subscription-manager");
		String expectedMsgRHSM = "This system is not registered to Red Hat Subscription Management. You can use subscription-manager to register.";
		String expectedMsgRHN; // comes from /usr/share/yum-plugins/rhnplugin.py (package yum-rhn-plugin)
		expectedMsgRHN = "This system is not registered with RHN Classic or RHN Satellite.\nYou can use rhn_register to register.\nRHN Satellite or RHN Classic support will be disabled.";	// yum-rhn-plugin-0.5.4.1-7.el5		// yum-rhn-plugin-0.9.1-48.el6
		// [root@jsefler-7 ~]# rpm -q --changelog yum-rhn-plugin | more
		// * Wed Jun 12 2013 Tomas Kasparek <tkasparek@redhat.com> 1.10.3-1
		// - rebranding RHN Proxy to Red Hat Proxy in client stuff
		// - rebranding RHN Satellite to Red Hat Satellite in client stuff
		if (clienttasks.isPackageVersion("yum-rhn-plugin", ">=", "2.0")) expectedMsgRHN = "This system is not registered with RHN Classic or Red Hat Satellite.\nYou can use rhn_register to register.\nRed Hat Satellite or RHN Classic support will be disabled.";	// yum-rhn-plugin-2.0.1-4.el7
//FIXME NOT CONVINCED THIS IF IS CORRECT		if (clienttasks.isPackageVersion("yum-rhn-plugin", ">=", "2.0.1-5")) expectedMsgRHN = "This system is not registered with RHN Classic or Red Hat Satellite. SystemId could not be acquired.\nYou can use rhn_register to register.\nRed Hat Satellite or RHN Classic support will be disabled.";	// yum-rhn-plugin-2.0.1-5.el7
		if (Arrays.asList(new String[]{"6.3","5.8","6.2","5.7","6.1"}).contains(clienttasks.redhatReleaseXY)) expectedMsgRHN = "This system is not registered with RHN."+"\n"+"RHN Satellite or RHN Classic support will be disabled.";	
		if (clienttasks.isPackageVersion("subscription-manager", "<", "1.12.2-1")) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When not registered to either RHN nor RHSM, the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
		else if (clienttasks.isPackageVersion("subscription-manager", ">=", "1.12.11-1")) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When not registered to either RHN nor RHSM, the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");  // Bug 1122772 was used to revert Bug 1058380
		else Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When not registered to either RHN nor RHSM, the subscription-manager yum plugin should NO LONGER inform that:\n"+expectedMsgRHSM+"\nBugzilla https://bugzilla.redhat.com/show_bug.cgi?id=1058380 was used to remove this usability messaging implemented for https://bugzilla.redhat.com/show_bug.cgi?id=818383");
		if (isRhnClientToolsInstalled) Assert.assertTrue((/*result.getStdout()+*/result.getStderr()).contains(expectedMsgRHN), "When not registered to either RHN nor RHSM, the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
	}
	
	@Test(	description="When registered to RHN but not RHSM, the subscription-manager yum plugin should inform that: This system is not registered to Red Hat Subscription Management. You can use subscription-manager to register.",
			groups={"YumPluginMessageCase_Tests","blockedByBug-818383","blockedByBug-832119","blockedByBug-830193","blockedByBug-830194","blockedByBug-906875","blockedByBug-924919"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)	
	public void YumPluginMessageCase2_Test() {
		clienttasks.unregister(null,null,null);
		if (!isRhnClientToolsInstalled) throw new SkipException("RHN Classic registration requires package '"+rhnClientTools+"' to be installed.");
		clienttasks.registerToRhnClassic(sm_rhnUsername, sm_rhnPassword, sm_rhnHostname);
		SSHCommandResult result = client.runCommandAndWait("yum repolist --enableplugin=rhnplugin --enableplugin=subscription-manager");
		String expectedMsgRHSM = "This system is not registered to Red Hat Subscription Management. You can use subscription-manager to register.";
		String expectedMsgRHN = null;	// comes from /usr/share/yum-plugins/rhnplugin.py (package yum-rhn-plugin)
		if (Float.valueOf(clienttasks.redhatReleaseXY)>=6.4) expectedMsgRHN = "This system is receiving updates from RHN Classic or RHN Satellite.";
		if (Float.valueOf(clienttasks.redhatReleaseXY)>=7.0) expectedMsgRHN = "This system is receiving updates from RHN Classic or Red Hat Satellite.";
		if (Integer.valueOf(clienttasks.redhatReleaseX)>=7 && doesStringContainMatches(sm_rhnHostname, "rhn\\.(.+\\.)*redhat\\.com")) {	// exceptional case when a rhel7 system attempts to register to RHN HOSTED 
			log.warning("With RHEL7 (and beyond), registration to RHN Classic (HOSTED) is no longer supported and therefore no base rhel channel (e.g. rhel-x86_64-server-7) will be available.");
			Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to RHN but not RHSM, the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
			Assert.assertFalse((result.getStdout()+result.getStderr()).contains(expectedMsgRHN), "When registered to RHN but not RHSM, the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
			Assert.assertTrue((result.getStdout()+result.getStderr()).contains(expectedMsgRHN_NoChannels), "On RHEL7... When registered to RHN but not RHSM, the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN_NoChannels+"\n");
			return;
		}
		if (clienttasks.isPackageVersion("subscription-manager", "<", "1.12.2-1")) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to RHN but not RHSM, the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
		else Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to RHN but not RHSM, the subscription-manager yum plugin should NO LONGER inform that:\n"+expectedMsgRHSM+"\nBugzilla https://bugzilla.redhat.com/show_bug.cgi?id=1058380 was used to remove this usability messaging implemented for https://bugzilla.redhat.com/show_bug.cgi?id=818383");
		if (expectedMsgRHN!=null) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHN), "When registered to RHN but not RHSM, the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
	}
	
	@Test(	description="When registered to RHSM (but not subscribed) but not RHN, the subscription-manager yum plugin should inform that: This system is registered to Red Hat Subscription Management, but is not receiving updates. You can use subscription-manager to assign subscriptions.",
			groups={"YumPluginMessageCase_Tests","blockedByBug-818383","blockedByBug-832119","blockedByBug-830193","blockedByBug-830194","blockedByBug-906875","blockedByBug-1058380","blockedByBug-1122772"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)	
	public void YumPluginMessageCase3A_Test() {
		clienttasks.register(sm_clientUsername,sm_clientPassword,sm_clientOrg,null,null,null,null,null,null,null,(List<String>)null,null,null,null,true,false,null,null, null);
		clienttasks.removeRhnSystemIdFile();
		SSHCommandResult result = client.runCommandAndWait("yum repolist --enableplugin=rhnplugin --enableplugin=subscription-manager");
		String expectedMsgRHSM = "This system is registered to Red Hat Subscription Management, but is not receiving updates. You can use subscription-manager to assign subscriptions.";	
		String expectedMsgRHN;	// comes from /usr/share/yum-plugins/rhnplugin.py (package yum-rhn-plugin)
		expectedMsgRHN = "This system is not registered with RHN Classic or RHN Satellite.\nYou can use rhn_register to register.\nRHN Satellite or RHN Classic support will be disabled.";
		// [root@jsefler-7 ~]# rpm -q --changelog yum-rhn-plugin | more
		// * Wed Jun 12 2013 Tomas Kasparek <tkasparek@redhat.com> 1.10.3-1
		// - rebranding RHN Proxy to Red Hat Proxy in client stuff
		// - rebranding RHN Satellite to Red Hat Satellite in client stuff
		if (clienttasks.isPackageVersion("yum-rhn-plugin", ">=", "2.0")) expectedMsgRHN = "This system is not registered with RHN Classic or Red Hat Satellite.\nYou can use rhn_register to register.\nRed Hat Satellite or RHN Classic support will be disabled.";
		if (Arrays.asList(new String[]{"6.3","5.8","6.2","5.7","6.1"}).contains(clienttasks.redhatReleaseXY)) expectedMsgRHN = "This system is not registered with RHN."+"\n"+"RHN Satellite or RHN Classic support will be disabled.";		
		if (clienttasks.isPackageVersion("subscription-manager", "<", "1.12.2-1")) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to RHSM (but not subscribed) but not RHN, the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
		else if (clienttasks.isPackageVersion("subscription-manager", ">=", "1.12.11-1")) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to RHSM (but not subscribed) but not RHN, the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");  // Bug 1122772 was used to revert Bug 1058380
		else Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to RHSM (but not subscribed) but not RHN, the subscription-manager yum plugin should NO LONGER inform that:\n"+expectedMsgRHSM+"\nBugzilla https://bugzilla.redhat.com/show_bug.cgi?id=1058380 was used to remove this usability messaging implemented for https://bugzilla.redhat.com/show_bug.cgi?id=818383");
		if (isRhnClientToolsInstalled) Assert.assertTrue((/*result.getStdout()+*/result.getStderr()).contains(expectedMsgRHN), "When registered to RHSM (but not subscribed) but not RHN, the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
	}
	
	@Test(	description="When registered to RHSM (and subscribed) but not RHN, the subscription-manager yum plugin should inform that: This system is registered to Red Hat Subscription Management, but is not receiving updates. You can use subscription-manager to assign subscriptions.",
			groups={"YumPluginMessageCase_Tests","blockedByBug-818383","blockedByBug-832119","blockedByBug-830193","blockedByBug-830194","blockedByBug-906875"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)	
	public void YumPluginMessageCase3B_Test() throws JSONException, Exception {
		clienttasks.register(sm_clientUsername,sm_clientPassword,sm_clientOrg,null,null,null,null,null,null,null,(List<String>)null,null,null,null,true,false,null,null, null);
		clienttasks.subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		clienttasks.removeRhnSystemIdFile();
		SSHCommandResult result = client.runCommandAndWait("yum repolist --enableplugin=rhnplugin --enableplugin=subscription-manager");
		String expectedMsgRHSM = "This system is receiving updates from Red Hat Subscription Management.";
		String expectedMsgRHN;	// comes from /usr/share/yum-plugins/rhnplugin.py (package yum-rhn-plugin)
		expectedMsgRHN = "This system is not registered with RHN Classic or RHN Satellite.\nYou can use rhn_register to register.\nRHN Satellite or RHN Classic support will be disabled.";
		// [root@jsefler-7 ~]# rpm -q --changelog yum-rhn-plugin | more
		// * Wed Jun 12 2013 Tomas Kasparek <tkasparek@redhat.com> 1.10.3-1
		// - rebranding RHN Proxy to Red Hat Proxy in client stuff
		// - rebranding RHN Satellite to Red Hat Satellite in client stuff
		if (clienttasks.isPackageVersion("yum-rhn-plugin", ">=", "2.0")) expectedMsgRHN = "This system is not registered with RHN Classic or Red Hat Satellite.\nYou can use rhn_register to register.\nRed Hat Satellite or RHN Classic support will be disabled.";
		if (Arrays.asList(new String[]{"6.3","5.8","6.2","5.7","6.1"}).contains(clienttasks.redhatReleaseXY)) expectedMsgRHN = "This system is not registered with RHN."+"\n"+"RHN Satellite or RHN Classic support will be disabled.";	
		//NOT TRUE ANYMORE Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to RHSM (and subscribed) but not RHN, the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
		Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to RHSM (and subscribed) but not RHN, the subscription-manager yum plugin NO LONGER informs that:\n"+expectedMsgRHSM+"\nFor justification, see https://bugzilla.redhat.com/show_bug.cgi?id=1017354#c12");	// commit 39eadae14eead4bb79978e52d38da2b3e85cba57 1017354: remove msg printed to stderr via yum
		if (isRhnClientToolsInstalled) Assert.assertTrue((/*result.getStdout()+*/result.getStderr()).contains(expectedMsgRHN), "When registered to RHSM (and subscribed) but not RHN, the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
	}
	
	@Test(	description="When registered to both RHN and RHSM (but not subscribed), the subscription-manager yum plugin should inform that: This system is registered to Red Hat Subscription Management, but is not receiving updates. You can use subscription-manager to assign subscriptions.",
			groups={"YumPluginMessageCase_Tests","blockedByBug-818383","blockedByBug-832119","blockedByBug-830193","blockedByBug-830194","blockedByBug-871146","blockedByBug-906875","blockedByBug-924919"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)	
	public void YumPluginMessageCase4A_Test() {
		clienttasks.register(sm_clientUsername,sm_clientPassword,sm_clientOrg,null,null,null,null,null,null,null,(List<String>)null,null,null,null,true,false,null,null, null);
		if (!isRhnClientToolsInstalled) throw new SkipException("RHN Classic registration requires package '"+rhnClientTools+"' to be installed.");
		clienttasks.registerToRhnClassic(sm_rhnUsername, sm_rhnPassword, sm_rhnHostname);
		SSHCommandResult result = client.runCommandAndWait("yum repolist --enableplugin=rhnplugin --enableplugin=subscription-manager");
		String expectedMsgRHSM = "This system is registered to Red Hat Subscription Management, but is not receiving updates. You can use subscription-manager to assign subscriptions.";
		String expectedMsgRHN = null;	// comes from /usr/share/yum-plugins/rhnplugin.py (package yum-rhn-plugin)
		if (Float.valueOf(clienttasks.redhatReleaseXY)>=6.4) expectedMsgRHN = "This system is receiving updates from RHN Classic or RHN Satellite.";
		if (Float.valueOf(clienttasks.redhatReleaseXY)>=7.0) expectedMsgRHN = "This system is receiving updates from RHN Classic or Red Hat Satellite.";
		if (Integer.valueOf(clienttasks.redhatReleaseX)>=7 && doesStringContainMatches(sm_rhnHostname, "rhn\\.(.+\\.)*redhat\\.com")) {	// exceptional case when a rhel7 system attempts to register to RHN HOSTED 
			log.warning("With RHEL7 (and beyond), registration to RHN Classic (HOSTED) is no longer supported and therefore no base rhel channel (e.g. rhel-x86_64-server-7) will be available.");
			Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to both RHN and RHSM (but not subscribed), the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
			Assert.assertFalse((result.getStdout()+result.getStderr()).contains(expectedMsgRHN), "When registered to both RHN and RHSM (but not subscribed), the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
			Assert.assertTrue((result.getStdout()+result.getStderr()).contains(expectedMsgRHN_NoChannels), "On RHEL7... When registered to both RHN and RHSM (but not subscribed), the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN_NoChannels+"\n");
			return;
		}
		if (clienttasks.isPackageVersion("subscription-manager", "<", "1.12.2-1")) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to both RHN and RHSM (but not subscribed), the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
		else Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to both RHN and RHSM (but not subscribed), the subscription-manager yum plugin should NO LONGER inform that:\n"+expectedMsgRHSM+"\nBugzilla https://bugzilla.redhat.com/show_bug.cgi?id=1058380 was used to remove this usability messaging implemented for https://bugzilla.redhat.com/show_bug.cgi?id=818383");
		if (expectedMsgRHN!=null) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHN), "When registered to both RHN and RHSM (but not subscribed), the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
	}
	
	@Test(	description="When registered to both RHN and RHSM (and subscribed), the subscription-manager yum plugin should inform that: This system is registered to Red Hat Subscription Management, but is not receiving updates. You can use subscription-manager to assign subscriptions.",
			groups={"YumPluginMessageCase_Tests","blockedByBug-818383","blockedByBug-832119","blockedByBug-830193","blockedByBug-830194","blockedByBug-906875","blockedByBug-924919"},
			enabled=true)
	//@ImplementsNitrateTest(caseId=)	
	public void YumPluginMessageCase4B_Test() throws JSONException, Exception {
		clienttasks.register(sm_clientUsername,sm_clientPassword,sm_clientOrg,null,null,null,null,null,null,null,(List<String>)null,null,null,null,true,false,null,null, null);
		clienttasks.subscribeToTheCurrentlyAvailableSubscriptionPoolsCollectively();
		if (!isRhnClientToolsInstalled) throw new SkipException("RHN Classic registration requires package '"+rhnClientTools+"' to be installed.");
		clienttasks.registerToRhnClassic(sm_rhnUsername, sm_rhnPassword, sm_rhnHostname);
		SSHCommandResult result = client.runCommandAndWait("yum repolist --enableplugin=rhnplugin --enableplugin=subscription-manager");
		String expectedMsgRHSM = "This system is receiving updates from Red Hat Subscription Management.";
		String expectedMsgRHN = null;	// comes from /usr/share/yum-plugins/rhnplugin.py (package yum-rhn-plugin)
		if (Float.valueOf(clienttasks.redhatReleaseXY)>=6.4) expectedMsgRHN = "This system is receiving updates from RHN Classic or RHN Satellite.";
		if (Float.valueOf(clienttasks.redhatReleaseXY)>=7.0) expectedMsgRHN = "This system is receiving updates from RHN Classic or Red Hat Satellite.";
		if (Integer.valueOf(clienttasks.redhatReleaseX)>=7 && doesStringContainMatches(sm_rhnHostname, "rhn\\.(.+\\.)*redhat\\.com")) {	// exceptional case when a rhel7 system attempts to register to RHN HOSTED 
			log.warning("With RHEL7 (and beyond), registration to RHN Classic (HOSTED) is no longer supported and therefore no base rhel channel (e.g. rhel-x86_64-server-7) will be available.");
			//NOT TRUE ANYMORE Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to both RHN and RHSM (and subscribed), the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
			Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to both RHN and RHSM (and subscribed), the subscription-manager yum plugin NO LONGER informs that:\n"+expectedMsgRHSM+"\nFor justification, see https://bugzilla.redhat.com/show_bug.cgi?id=1017354#c12");	// commit 39eadae14eead4bb79978e52d38da2b3e85cba57 1017354: remove msg printed to stderr via yum
			Assert.assertFalse((result.getStdout()+result.getStderr()).contains(expectedMsgRHN), "When registered to both RHN and RHSM (and subscribed), the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
			Assert.assertTrue((result.getStdout()+result.getStderr()).contains(expectedMsgRHN_NoChannels), "On RHEL7... When registered to both RHN and RHSM (and subscribed), the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN_NoChannels+"\n");
			return;
		}
		//NOT TRUE ANYMORE Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHSM), "When registered to both RHN and RHSM (and subscribed), the subscription-manager yum plugin should inform that:\n"+expectedMsgRHSM+"\n");
		Assert.assertTrue(!(result.getStdout()+result.getStderr()).contains(expectedMsgRHSM), "When registered to both RHN and RHSM (and subscribed), the subscription-manager yum plugin NO LONGER informs that:\n"+expectedMsgRHSM+"\nFor justification, see https://bugzilla.redhat.com/show_bug.cgi?id=1017354#c12");	// commit 39eadae14eead4bb79978e52d38da2b3e85cba57 1017354: remove msg printed to stderr via yum
		if (expectedMsgRHN!=null) Assert.assertTrue((result.getStdout()/*+result.getStderr()*/).contains(expectedMsgRHN), "When registered to both RHN and RHSM (and subscribed), the rhnplugin yum plugin should inform that:\n"+expectedMsgRHN+"\n");
	}
	
	
	// Candidates for an automated Test:
	// TODO Bug 872310 - yum plugins subscription-manger and rhnplugin should agree to print to stdout or stderr	// when this bug is fixed, simply update the YumPluginMessageCase*_Tests  https://github.com/RedHatQE/rhsm-qe/issues/165
	
	
	// Configuration methods ***********************************************************************
	
	@AfterGroups(groups={"setup"}, value={"InteroperabilityRegister_Test","YumPluginMessageCase_Tests"})
	public void removeRhnSystemIdFileAfterGroups() {
		if (clienttasks==null) return;
		clienttasks.removeRhnSystemIdFile();
	}
	
	@BeforeClass(groups={"setup"})
	public void setupBeforeClass() {
		if (clienttasks==null) return;
		
		// is rhn-client-tools package installed?
		isRhnClientToolsInstalled = clienttasks.isPackageInstalled(rhnClientTools);	// provides /etc/sysconfig/rhn/up2date and /usr/sbin/rhnreg_ks
		
		// make dir /etc/sysconfig/rhn/ when rhn-client-tools package is not installed to enable some more tesing
		if (!isRhnClientToolsInstalled) {
			String rhnDir =  new File(clienttasks.rhnSystemIdFile).getParent();
			client.runCommandAndWait("mkdir -p "+rhnDir);
		}
		
		
		// make sure we have the RHN-ORG-TRUSTED-SSL-CERT for the rhn/satellite server
		/*
		 * 	1. Set automation parameters:
		 * 		sm.rhn.hostname : https://sat-56-server.usersys.redhat.com
		 *		sm.rhn.username : admin
		 *		sm.rhn.password : *****
		 *  2. Use firefox to login to the Satellite account
		 *      https://sat-56-server.usersys.redhat.com/rhn/Login.do
		 *      do whatever work you need to there
		 *  3. Get the CA cert from Satellite and install it onto your client
		 *      wget --no-verbose --no-check-certificate --output-document=/usr/share/rhn/RHN-ORG-TRUSTED-SSL-CERT_sat-56-server.usersys.redhat.com https://sat-56-server.usersys.redhat.com/pub/RHN-ORG-TRUSTED-SSL-CERT
		 *  4. Update the /etc/sysconfig/rhn/up2date with
		 *      sslCACert=RHN-ORG-TRUSTED-SSL-CERT_sat-56-server.usersys.redhat.com
		 */
		// Get the CA cert from Satellite and install it onto your client
		if (!sm_rhnHostname.isEmpty()) {
			if (!doesStringContainMatches(sm_rhnHostname, "rhn\\.(.+\\.)*redhat\\.com")) {	// if (sm_rhnHostname.startsWith("http") { 	// indicates that we are migrating from a non-hosted rhn server - as opposed to rhn.code.stage.redhat.com (stage) or rhn.redhat.com (production)
				String satHostname = sm_rhnHostname.split("/")[2];	// https://sat-56-server.usersys.redhat.com
				String satCaCertPath = "/usr/share/rhn/RHN-ORG-TRUSTED-SSL-CERT"+"_"+satHostname;
				RemoteFileTasks.runCommandAndAssert(client,"wget --no-verbose --no-check-certificate --output-document="+satCaCertPath+" "+sm_rhnHostname+"/pub/RHN-ORG-TRUSTED-SSL-CERT",Integer.valueOf(0),null,"-> \""+satCaCertPath+"\"");
				
				// Update /etc/sysconfig/rhn/up2date->sslCACert with satCaCertPath
				clienttasks.updateConfFileParameter(clienttasks.rhnUp2dateFile, "sslCACert", satCaCertPath);	// sslCACert[comment]=The CA cert used to verify the ssl server
			}
		}
		
		// make sure the rhnplugin conf is enabled
		clienttasks.updateConfFileParameter(clienttasks.yumPluginConfFileForRhn, "enabled","1");
	}
	
	
	// Protected methods ***********************************************************************
	protected String expectedMsgRHN_NoChannels = "This system is not subscribed to any channels.\nRHN channel support will be disabled.";
	protected final String rhnClientTools = "rhn-client-tools";
	protected boolean isRhnClientToolsInstalled = true;	// assume
	
	
	
	// Data Providers ***********************************************************************
	
}
