package rhsm.base;

/**
 * system - a machine (default) <BR>
 * person - a dude or dudette <BR>
 * candlepin - a downstream server <BR>
 * domain - an IP domain <BR>
 * 
 * @author jsefler
 */
public enum ConsumerType  { system, person, candlepin, domain, hypervisor, RHUI, headpin, katello, share }


//System (a machine)
//Person (a dude or dudette)
//Domain (an IP domain)
//Candlepin (a downstream server)