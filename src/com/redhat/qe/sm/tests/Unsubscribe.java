package com.redhat.qe.sm.tests;

import org.testng.annotations.Test;

import com.redhat.qe.auto.tcms.ImplementsTCMS;
import com.redhat.qe.auto.testopia.Assert;
import com.redhat.qe.sm.tasks.Pool;
import com.redhat.qe.tools.RemoteFileTasks;

public class Unsubscribe extends Subscribe{
	@Test(description="subscription-manager-cli: unsubscribe client to an entitlement using product ID",
			dependsOnMethods="EnableYumRepoAndVerifyContentAvailable_Test",
			groups={"sm"})
	@ImplementsTCMS(id="41688")
	public void UnsubscribeFromValidSubscriptionsByProductID_Test(){
		this.subscribeToAllSubscriptions(false);
		this.unsubscribeFromAllSubscriptions(false);
	}
	
	@Test(description="Copy entitlement certificate in /etc/pki/entitlement/product after unsubscribe",
			dependsOnMethods="EnableYumRepoAndVerifyContentAvailable_Test",
			groups={"sm"})
	@ImplementsTCMS(id="41903")
	public void UnsubscribeAndReplaceCert_Test(){
		sshCommandRunner.runCommandAndWait("killall -9 yum");
		String randDir = "/tmp/sm-certs-"+Integer.toString(this.getRandInt());
		this.subscribeToAllSubscriptions(false);
		
		//copy certs to temp dir
		sshCommandRunner.runCommandAndWait("rm -rf "+randDir);
		sshCommandRunner.runCommandAndWait("mkdir -p "+randDir);
		sshCommandRunner.runCommandAndWait("cp /etc/pki/entitlement/product/* "+randDir);
		
		this.unsubscribeFromAllSubscriptions(false);
		
		sshCommandRunner.runCommandAndWait("cp -f "+randDir+"/* /etc/pki/entitlement/product");
		RemoteFileTasks.runCommandExpectingNonzeroExit(sshCommandRunner,
				"yum repolist",Long.valueOf(2*60000));
	}
	
	/*
	@Test(description="subscription-manager-cli: unsubscribe client to an entitlement using pool ID",
			dependsOnMethods="EnableYumRepoAndVerifyContentAvailable_Test",
			groups={"sm"})
	@ImplementsTCMS(id="41689")
	public void UnsubscribeFromValidSubscriptionsByPoolID_Test(){
		this.subscribeToAllSubscriptions(true);
		this.unsubscribeFromAllSubscriptions(true);
	}*/
	
	@Test(description="Unsubscribe product entitlement and re-subscribe",
			dependsOnMethods="UnsubscribeFromValidSubscriptionsByProductID_Test",
			groups={"sm"})
	@ImplementsTCMS(id="41898")
	public void ResubscribeAfterUnsubscribe_Test(){
		this.subscribeToAllSubscriptions(false);
	}
}
