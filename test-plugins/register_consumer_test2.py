#
# Copyright (c) 2013 Red Hat, Inc.
#
# This software is licensed to you under the GNU General Public License,
# version 2 (GPLv2). There is NO WARRANTY for this software, express or
# implied, including the implied warranties of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
# along with this software; if not, see
# http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
#
# Red Hat trademarks are not licensed under GPLv2. No permission is
# granted to use or replicate Red Hat trademarks that are incorporated
# in this software or its documentation.
#

from subscription_manager.base_plugin import SubManPlugin
requires_api_version = "1.0"

# imported to support logging in subscription-manager >= 1.17.10-1 from Bug 1334916: Move logging configuration to rhsm.conf
import logging
log = logging.getLogger('rhsm-app.' + __name__)


class RegisterConsumerTestPlugin(SubManPlugin):
    """Plugin triggered when a consumer registers"""
    name = "register_consumer_test"

    def pre_register_consumer_hook(self, conduit):
        """`pre_register_consumer` hook

        Args:
            conduit: A RegistrationConduit()
        """
        conduit.log.info("Running pre_register_consumer_hook 2: system name %s is about to be registered." % conduit.name)
        log.info        ("Running pre_register_consumer_hook 2: system name %s is about to be registered." % conduit.name)
        conduit.log.info("Running pre_register_consumer_hook 2: consumer facts count is %s" % len(conduit.facts))
        log.info        ("Running pre_register_consumer_hook 2: consumer facts count is %s" % len(conduit.facts))
        conduit.log.info("consumer conduit facts:\n%s" % conduit.facts)
        log.info        ("consumer conduit facts:\n%s" % conduit.facts)

    def post_register_consumer_hook(self, conduit):
        """`post_register_consumer` hook

        Args:
            conduit: A RegistrationConduit()
        """
        conduit.log.info("Running post_register_consumer_hook 2: consumer uuid %s is now registered." % conduit.consumer['uuid'])
        log.info        ("Running post_register_consumer_hook 2: consumer uuid %s is now registered." % conduit.consumer['uuid'])

