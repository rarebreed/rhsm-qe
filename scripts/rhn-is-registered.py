#!/usr/bin/python

# Author jsefler
# Given credentials and a hosted RHN server, this script is used to report if a systemid is currently registered.


# avoid: Unexpected error: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed (_ssl.c:579)
# reference: https://dnaeon.github.io/disable-python-ssl-verification/
import ssl
try: 
    _create_unverified_https_context = ssl._create_unverified_context
except AttributeError:
    # Legacy Python that doesn't verify HTTPS certificates by default
    pass
else:
    # Handle target environment that doesn't support HTTPS verification
    ssl._create_default_https_context = _create_unverified_https_context


import sys, time
from xmlrpclib import Server
from optparse import OptionParser

usage = "Usage %prog [OPTIONS] systemid"
parser = OptionParser(usage=usage)
parser.add_option("-u", "--username", dest="username", help="Username")
parser.add_option("-p", "--password", dest="password", help="Password")
#parser.add_option("-i", "--systemid", dest="systemid", help="System id to check for registration")
parser.add_option("-s", "--serverurl", dest="serverurl", help="Server URL https://rhn.redhat.com", default="https://rhn.redhat.com")
(options, args) = parser.parse_args()

if not options.username or not options.password or not args:
   parser.print_usage()
   sys.exit(1) 

systemid = args[0];



# create an api connection to the server
# RHN API documentation: https://access.stage.redhat.com/knowledge/docs/Red_Hat_Network/
client = Server("%s/rpc/api/" % options.serverurl)
# sessionKey = client.auth.login(options.username, options.password)
sessionKey = None
count = 0
while (sessionKey == None):
    if count > 10:
        print "Giving up trying to authenticate to RHN API..."
        sys.exit(-1)
    try:
        sessionKey = client.auth.login(options.username, options.password)
    except Exception, e:
        print "Unexpected error:", e
        count += 1
        time.sleep(10)

# loop through all of the user's systems looking for systemid
system_list = client.system.listUserSystems(sessionKey)
registered = False;
for system in system_list:
    if str(system["id"]) == str(systemid):
        registered = True;
        break;

print registered;
