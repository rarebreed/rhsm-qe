package rhsm.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.redhat.qe.tools.abstraction.AbstractCommandLineData;

/**
 * A Translation object is a key value pair where the key is the msgid and the value is the adjacent msgstr
 * found in a translation file like /usr/share/locale/de_DE/LC_MESSAGES/rhsm.mo that can be decompiled using
 * msgunfmt --no_wrap rhsm.mo_file
 * @author jsefler
 *
 */
public class Translation extends AbstractCommandLineData {
	
	// abstraction fields
	public String msgid;
	public String msgstr;
	
	
	public Translation(Map<String, String> translationMap) {
		super(translationMap);
	}
	
	public Translation(String msgid, String msgstr) {
		super(null);
		this.msgid = msgid;
		this.msgstr = msgstr;
	}
	
	@Override
	public String toString() {
		
		String string = "";
		if (msgid != null)	string += String.format(" %s='%s'", "msgid",msgid);
		if (msgstr != null)	string += String.format(" %s='%s'", "msgstr",msgstr);

		return string.trim();
	}
	

	
	/**
	 * @param msgunfmt - stdout from "msgunfmt /usr/share/locale/de/LC_MESSAGES/rhsm.mo"
	 * @return
	 */
	static public List<Translation> parse(String msgunfmtString) {
		
		/* 
		[root@qe-blade-06 ~]# msgunfmt /usr/share/locale/de/LC_MESSAGES/rhsm.mo
		msgid ""
		msgstr ""
		"Project-Id-Version: l 10n\n"
		"Report-Msgid-Bugs-To: \n"
		"POT-Creation-Date: 2012-05-16 08:40-0400\n"
		"PO-Revision-Date: 2012-04-12 07:56-0400\n"
		"Last-Translator: hedda <hedda@fedoraproject.org>\n"
		"Language-Team: de_DE <kde-i18n-doc@kde.org>\n"
		"MIME-Version: 1.0\n"
		"Content-Type: text/plain; charset=UTF-8\n"
		"Content-Transfer-Encoding: 8bit\n"
		"Language: de-DE\n"
		"Plural-Forms: nplurals=2; plural=(n != 1);\n"
		"X-Generator: Zanata 1.5.0\n"

		msgid ""
		"\n"
		"Attempting to register system to Certificate-based RHN ..."
		msgstr ""
		"\n"
		"Registrierung des Systems beim zertifikatsbasierten RHN wird versucht ..."
		
		msgid "    Entitled Repositories in %s"
		msgstr "Berechtigte Repositorys in %s"
		
		msgid "%prog [options]"
		msgstr "%prog [Optionen]"
		
		msgid "%s (first date of invalid entitlements)"
		msgstr "%s (erster Tag mit ungültigen Berechtigungen)"
		
		msgid "<b>Account Number:</b>"
		msgstr "<b>Account-Nummer:</b>"
		
		msgid "Product entitlement certificates <i>valid</i> until %s"
		msgstr "Produkt-Berechtigungszertifikate <i>gültig</i> bis %s"
		
		msgid "Account Number:       \t%-25s"
		msgstr "Accountnummer:       \t%-25s"
		
		msgid "Proxy _Username:"
		msgstr "Proxy-Ben_utzername:"
		
		msgid ""
		"Error displaying Subscription Assistant. Please see /var/log/rhsm/rhsm.log "
		"for more information."
		msgstr ""
		"Fehler beim Anzeigen des Subskriptions-Assistenten. Siehe /var/log/rhsm/rhsm."
		"log  für weitere Informationen."
		
		msgid "System '%s' successfully registered to Certificate-based RHN.\n"
		msgstr "System '%s' erfolgreich beim zertifikatsbasierten RHN registriert.\n"
		
		msgid ""
		"Tip: Forgot your login or password? Look it up at http://red.ht/lost_password"
		msgstr ""
		"Tipp: Login oder Passwort vergessen? Rufen Sie es unter http://red.ht/"
		"lost_password ab."

		msgid ""
		"You will need to manually subscribe this system after completing firstboot. "
		"Subscription Manager can be found in <b>System > Administration > "
		"Subscription Manager</b>."
		msgstr ""
		"Sie müssen dieses System nach Abschluss von Firstboot manuell subskribieren. "
		"Sie finden den Subscription Manager in <b>System > Administration > "
		"Subscription Manager</b>."
		
		msgid "[] - Default value in use"
		msgstr "[] - Standardwert wird verwendet"
		
		msgid "_Check all"
		msgstr "_Alle auswählen"
		
		msgid "_Close"
		msgstr "S_chließen"
		
		msgid "_Help"
		msgstr "_Hilfe"
		
		msgid "facts_view"
		msgstr "Fakten_Ansicht"
		*/
		
		/*
		msgid "Could not read installation number from /etc/sysconfig/rhn/install-num.  Aborting."
		msgstr " /etc/sysconfig/rhn/install-num থেকে ইনস্টলেশন সংখ্যা পড়া যায়নি। কর্ম পরিত্যাগ করা হচ্ছে।"

		msgid "Covered by contract %s through %s"
		msgid_plural "Covered by contracts %s through %s"
		msgstr[0] "%s চুক্তি দ্বারা %s অব্দি সুরক্ষিত"
		msgstr[1] "%s চুক্তিগুলি দ্বারা %s অব্দি সুরক্ষিত"

		msgid "Current identity is: %s"
		msgstr "বর্তমানে ব্যবহৃত পরিচিতি: %s"
		*/
		
		Map<String,String> regexes = new HashMap<String,String>();
		
		// abstraction field	regex pattern (with a capturing group) Note: the captured group will be trim()ed
		regexes.put("msgid",	"msgid (\".*\"(\\n\".*\")*)");
		regexes.put("msgstr",	"(?:msgstr|msgstr\\[0\\]) (\".*\"(\\n\".*\")*)");		//"msgstr (\".*\"(\\n\".*\")*)"); THIS FAILED TO CAPTURE THE msgstr[0] WHEN THERE WAS A PLURAL
		
		// TODO CURRENTLY THIS ROUTINE FAILS TO FIND THE msgid_plural AND msgstr[1]
		
		List<Map<String,String>> translationMapList = new ArrayList<Map<String,String>>();
		for(String field : regexes.keySet()){
			Pattern pat = Pattern.compile(regexes.get(field), Pattern.MULTILINE);
			addRegexMatchesToList(pat, msgunfmtString, translationMapList, field);
		}
		
		List<Translation> translations = new ArrayList<Translation>();
		for(Map<String,String> translationMap : translationMapList) {
			
			// dequote the multi-line values
			for (String key : translationMap.keySet()) {
				String value = translationMap.get(key);
				String dequotedValue = value.replaceAll("\"\n\"", "").replaceFirst("^\"", "").replaceFirst("\"$", "").replaceAll("\\\\n", "\n");
				translationMap.put(key, dequotedValue);
			}
			translations.add(new Translation(translationMap));
		}
		return translations;
	}
}
