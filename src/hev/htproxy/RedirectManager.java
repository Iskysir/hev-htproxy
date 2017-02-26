/* RedirectManager.java
 * Heiher <r@hev.cc>
 */

package hev.htproxy;

import java.util.Set;
import java.lang.Process;
import java.lang.ProcessBuilder;
import java.lang.InterruptedException;
import java.io.BufferedInputStream;
import java.io.IOException;
import android.content.Context;

public class RedirectManager {
	private static final int TYPE_APPEND = 1;
	private static final int TYPE_CHECK = 2;
	private static final int TYPE_DELETE = 3;

	private static final String cmd_iptables = "iptables -t nat ";

	public static int runSuperCmd(String cmd) {
		ProcessBuilder pb = new ProcessBuilder();
		int exitValue = -1;

		try {
			pb.command("su", "-c", cmd);
			Process p = pb.start();
			p.waitFor();
			BufferedInputStream in = new BufferedInputStream(p.getErrorStream());
			exitValue = p.exitValue();
			if (0 < in.available())
			  exitValue |= Integer.MIN_VALUE;
			p.destroy();
		} catch (IOException e) {
		} catch (InterruptedException e) {
		}

		return exitValue;
	}

	public static String[] generateCmds(int type, Context context) {
		String cmd_type;
		switch (type) {
		case TYPE_APPEND:
			cmd_type = " -A ";
			break;
		case TYPE_CHECK:
			cmd_type = " -C ";
			break;
		case TYPE_DELETE:
		default:
			cmd_type = " -D ";
			break;
		}

		Preferences prefs = new Preferences(context);
		Set<String> bypass_addresses = prefs.getBypassAddresses();
		Set<String> applications = prefs.getApplications();
		int i = 0, cmds_size = 9 + bypass_addresses.size() + applications.size();
		cmds_size += (type != TYPE_CHECK) ? 2 : 0;
		String[] cmds = new String[cmds_size];
		if (type == TYPE_APPEND) {
			cmds[i++] = cmd_iptables + "-N HTPROXY";
			cmds[i++] = cmd_iptables + "-I OUTPUT -j HTPROXY";
		}
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d " +
			prefs.getServerAddress() + "/32 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 0.0.0.0/8 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 10.0.0.0/8 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 127.0.0.0/8 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 169.254.0.0/16 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 172.16.0.0/12 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 192.168.0.0/16 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 224.0.0.0/4 -j RETURN";
		cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d 240.0.0.0/4 -j RETURN";
		for (String addr : bypass_addresses)
		  cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -d " + addr + " -j RETURN";
		for (String app : applications) {
			String owner = "", uid = app, ptype = "";

			if (app.startsWith("#")) {
				cmds[i++] = "";
				continue;
			}
			if (app.contains(":")) {
				String[] strs = app.split(":");
				uid = strs[0];
				ptype = strs[1];
			}
			if (!uid.equalsIgnoreCase("global"))
			  owner = "-m owner --uid-owner " + uid;
			if (ptype.equalsIgnoreCase("dns")) {
				String op = (type == TYPE_APPEND) ? " -I " : cmd_type;
				cmds[i++] = cmd_iptables + op + "HTPROXY -p udp --dport 53 "
					+ owner + " -j REDIRECT --to-port " +
					Integer.toString(prefs.getDNSFwdPort());
			} else {
				cmds[i++] = cmd_iptables + cmd_type + "HTPROXY -p tcp " + owner +
					" -j REDIRECT --to-port " +
					Integer.toString(prefs.getTProxyPort());
			}
		}

		if (type == TYPE_DELETE) {
			cmds[i++] = cmd_iptables + cmd_type + "OUTPUT -j HTPROXY";
			cmds[i++] = cmd_iptables + "-X HTPROXY";
		}

		return cmds;
	}

	public static boolean isSupported() {
		if (0 != runSuperCmd(cmd_iptables + "-I OUTPUT 1 -p tcp -j REDIRECT"))
		  return false;
		if (0 != runSuperCmd(cmd_iptables + "-D OUTPUT 1"))
		  return false;

		return true;
	}

	public static boolean isEnabled(Context context) {
		String[] cmds = generateCmds(TYPE_CHECK, context);
		for (String cmd : cmds) {
			if (0 != runSuperCmd(cmd))
			  return false;
		}

		return true;
	}

	public static boolean setEnabled(boolean enable, Context context) {
		boolean retValue = true;
		int type = enable ? TYPE_APPEND : TYPE_DELETE;
		String[] cmds = generateCmds(type, context);
		for (String cmd : cmds)
		  retValue = (0 == runSuperCmd(cmd)) ? true : false;

		/* enable failed, clear */
		if (enable && !retValue) {
			cmds = generateCmds(TYPE_DELETE, context);
			for (String cmd : cmds)
			  runSuperCmd(cmd);
		}

		return (enable) ? retValue : true;
	}
}
