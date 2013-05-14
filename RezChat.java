/*
 *  RezChat - standalone chat server, use browser to chat
 *
 *  Version 1.0 (2006-11-12)
 *
 *  Copyright (C) 2006 Reza Naghibi
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */

import java.net.*;
import javax.net.ssl.*;
import java.util.*;
import java.io.*;
import java.text.*;
import java.lang.reflect.*;

public class RezChat extends Thread
{
	//Chat server settings
	public static int PORT=8080;
	public static String FILENAME="chat.log";

	//This is the password, set to blank to disable
	public static String RC_PASSWORD="";

	//SSL (HTTPS) settings, set to false to use plain HTTP
	public static boolean SSL=false;
	public static String SSLKEY="ssl.key";
	public static String SSLPASSWORD="sslpw";

	//CSS and JS chat extensions
	//FORCE=true to load extensions (EXT="" to disable)
	//FORCE=false to allow user to load their own
	public static boolean RC_DEFJSFORCE=false;
	public static String RC_DEFJSEXT="http://www.rezsoft.org/rezchat/extensions/tools.js";
	public static boolean RC_DEFCSSFORCE=false;
	public static String RC_DEFCSSEXT="http://www.rezsoft.org/rezchat/extensions/small.css";

	//Look and feel settings, used by code
	public static String RC_DEFAULTNAME="Somebody";
	public static int RC_NUMLINES=160;
	public static int RC_CHATLEFT=20;
	public static int RC_CHATDOWN=20;
	public static int RC_CHATWIDTH=650;
	public static int RC_CHATHEIGHT=300;
	public static int RC_CHATNAMEWIDTH=100;
	public static String RC_CHATBACKGROUND="#99CCFF";
	public static String RC_CHATSCROLL="#99CCFF";	
	public static String RC_CHATNAMEBACKGROUND="#3399FF";
	public static int RC_CHATSUBWIDTH=RC_CHATWIDTH-RC_CHATNAMEWIDTH;
	public static int RC_CHATNAMELEFT=RC_CHATLEFT+RC_CHATSUBWIDTH;
	public static int RC_FORMHEIGHT=RC_CHATHEIGHT+35;

	//Global static vars
	private static boolean init=false;
	private static Vector<Vector<String>> chat;
	private static boolean kill;
	private static Hashtable<String,Vector<String>> users;
	private static Hashtable<String,String> code;
	private static ServerSocket serverSocket;
	private static long laction;
	private static Object cond;

	//Global instance vars
	private Socket s;

	//real time variables
	private String RT_NAME;
	private String RT_MSG;
	private String RT_SID;
	private String RT_PW;
	private String RT_JS;
	private String RT_IJS;
	private String RT_CSS;
	private String RT_ICSS;

	/*
	 * main()
	 */
	public static void main(String[] args)
	{
		startRezChat();
	}

	/*
	 * Runs the chat server
	 */
	public static void startRezChat()
	{
		System.out.println("RezChat: STARTED");
		print("RezChat: STARTED");

		init();

		try
		{
			if(SSL)
			{
				System.setProperty("javax.net.ssl.keyStore",SSLKEY);
				System.setProperty("javax.net.ssl.keyStorePassword",SSLPASSWORD);
				serverSocket=SSLServerSocketFactory.getDefault().createServerSocket(PORT,10);
			}
			else
				serverSocket=new ServerSocket(PORT,10);
		}
		catch (IOException e)
		{
			System.out.println("ERROR: Couldn't open port: "+PORT);
			print("ERROR: Couldn't open port: "+PORT,e);
			return;
		}

		try
		{
			while(!kill)
			{
				new RezChat(serverSocket.accept());
			}
		}
		catch(Exception ex) { print("EX: RezChat()",ex); }

		try { serverSocket.close(); }
		catch(Exception ex) {}
	}


	/*
	 * inits the chat server
	 */
	public static void init()
	{
		if(init)
			return;

		users=new Hashtable<String,Vector<String>>();
		serverSocket=null;
		kill=false;
		laction=0;
		cond=new Object();
		new RezChat();
	}

	/*
	 * instance init
	 */
	public RezChat()
	{
		if(init)
			return;

		init=true;
		loadCode();
		loadOldChat();
	}

	/*
	 * stop the server
	 */
	public static void kill()
	{
		print("STOP requested");
		kill=true;
		try { serverSocket.close(); }
		catch(Exception ex) {}
	}

	/*
	 * Serves the chat via a socket
	 */
	public RezChat(Socket s)
	{
		this.s=s;
		start();
	}

	/*
	 * run()
	 */
	public void run()
	{
		try
		{
			PrintWriter out=new PrintWriter(s.getOutputStream(),true);
			BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));

			String ret;
			try
			{
				ret=service(parseSocket(in),(new Date()).getTime());
				out.print(ret);
			}
			catch(Exception ex)
			{
				out.print(getErrorHeader());
				print("EX: service()",ex);
			}
			out.flush();

			out.close();
			in.close();
		}
		catch(Exception ex) { print("EX: run()",ex); }

		try { s.close(); }
		catch(Exception ex) {}
	}

	/*
	 * reads the socket, creates a hashtable of variables
	 */
	private Hashtable<String,String> parseSocket(BufferedReader in) throws Exception
	{
		Vector<String> request=new Vector<String>();
		String action="";
		String params="";
		String inputLine;

		while((inputLine=in.readLine())!=null && inputLine.length()>0)
		{
			request.add(inputLine);
		}

		if(request.size()==0)
			throw new Exception("Empty request");

		String header=request.elementAt(0);
		StringTokenizer st=new StringTokenizer(header," ");

		if(!st.hasMoreTokens())
			throw new Exception("Malformed HTTP header");

		String type=st.nextToken();
		if(type.equals("GET") && st.hasMoreTokens())
		{
			StringTokenizer st1=new StringTokenizer(st.nextToken(),"?");
			action=st1.nextToken().toLowerCase();
			if(st1.hasMoreTokens())
				params=st1.nextToken();
		}
		else if(type.equals("POST"))
		{
			int max=0;
			char rc;

			if(st.hasMoreTokens())
				action=st.nextToken().toLowerCase();

			try
			{
				for(int i=0;i<request.size();i++)
					if(request.elementAt(i).startsWith("Content-Length"))
						max=Integer.parseInt(request.elementAt(i).split(" ")[1]);
			}
			catch(Exception ex) { max=0; }

			if(max!=0)
			{
				StringBuffer sb=new StringBuffer(max);
				try
				{
					for(;max>0;max--)
						sb.append((char)in.read());
				}
				catch(Exception ex) {}
				params=sb.toString();
			}
		}
		else
			throw new Exception("Unsupported HTTP");

		if(action.startsWith("/"))
			action=action.substring(1);

		if(action.equals("load"))
			throw new Exception("Security violation");

		if(!action.startsWith("pull"))
			print("ACTION:"+action+","+params);

		return genHT(action,params);
	}

	/*
	 * generates a hashtable
	 */
	private Hashtable<String,String> genHT(String action,String params)
	{
		Hashtable<String,String> ht=new Hashtable<String,String>();

		String[] args=params.split("&");
		for(int i=0;i<args.length;i++)
		{
			String[] value=args[i].split("=");
			if(value.length==0)
				continue;
			else if(value.length==1)
				ht.put(value[0].toLowerCase(),"");
			else
				ht.put(value[0].toLowerCase(),value[1].replace("+","%20"));
		}

		ht.put("action",action);

		return ht;
	}

	/*
	 * Services the request
	 */
	private String service(Hashtable<String,String> ht,long ts) throws Exception
	{
		String action;
		String name;
		String msg;
		String pw;
		String sid;
		String js;
		String css;
		String hostname;
		boolean pwpass=false;
		long lmin;

		action=htget(ht,"action","");
		RT_NAME=name=htget(ht,"name",RC_DEFAULTNAME);
		RT_MSG=msg=htget(ht,"text","");
		RT_PW=pw=htget(ht,"pw","");
		RT_SID=sid=htget(ht,"sid",(new Date()).getTime()+"");
		RT_JS=RT_IJS=js=htget(ht,"js","",true);
		RT_CSS=RT_ICSS=css=htget(ht,"css","",true);
		lmin=Long.parseLong(htget(ht,"lmin","0"));

		if(action.startsWith("code/"))
			return getCode(action.substring(5));

		if(action.startsWith("local/"))
			return getFile(action.substring(6));

		if(RC_DEFJSFORCE)
			RT_JS=RT_IJS=js=RC_DEFJSEXT;
		if(RC_DEFCSSFORCE)
			RT_CSS=RT_ICSS=css=RC_DEFCSSEXT;
		if(js.length()>0)
			RT_IJS="\n<script src=\""+js+"\"></script>";
		if(css.length()>0)
			RT_ICSS="\n<link rel=stylesheet type=text/css href=\""+css+"\" />";

		if(pw.equals(RC_PASSWORD))
			pwpass=true;

		if(pwpass && action.equals("pullchat") && users.get(sid)==null)
			hostname=s.getInetAddress().getCanonicalHostName();
		else
			hostname="???";


		if((action.equals("chat") && pwpass) || action.equals("load"))
		{
			long sig;
			synchronized(cond)
			{
				sig=laction;
				if(!action.equals("load"))
				{
					actionUser(sid,ts);
					updateUser(name,sid,ts,hostname);
				}
				addChat(name,msg,ts);
				if(laction>sig)
					signalEvent();
			}
			return getResponseHeader("html")+"OK";
		}
		else if((action.equals("pullchat") || action.equals("pullchatwait")) && pwpass)
		{
			long sig;
			synchronized(cond)
			{
				sig=laction;
				actionUser(sid,ts);
				if(action.equals("pullchat"))
					updateUser(name,sid,ts,hostname);
				if(laction>sig)
					signalEvent();
			}
			if(action.equals("pullchatwait"))
				return waitForChat(lmin,sid);
			else
				return genChatXML(lmin);
		}
		else if(action.equals(""))
		{
			if(!pwpass)
				return getCode("password.html");
			else
				return getCode("rezchat.html");
		}
		else throw new Exception("Bad request: "+action);
	}

	/*
	 * add chat to the log
	 */
	private void addChat(String name,String msg,long ts)
	{
		if(msg.equals(""))
			return;
		Long nsec=new Long(ts);
		Vector<String> v=new Vector<String>();
		v.add(nsec.toString());
		v.add(name);
		v.add(msg);
		int pos;
		for(pos=0;pos<chat.size();pos++)
			if(Long.parseLong(chat.elementAt(pos).elementAt(0))>ts)
				break;
		chat.add(pos,v);

		while(chat.size()>RC_NUMLINES)
			chat.remove(0);

		if(ts>laction)
			laction=ts;
	}

	/*
	 * sets the users timestamp
	 */
	private void actionUser(String sid,long secs)
	{
		if(users.get(sid)!=null)
		{
			Long nsec=new Long(secs);
			Vector<String> v=users.get(sid);
			v.remove(1);
			v.add(1,nsec.toString());
			users.put(sid,v);
		}

		exitUser(secs);
	}

	/*
	 * remove inactive users
	 */
	private void exitUser(long secs)
	{
		Enumeration<String> keys=users.keys();
		while(keys.hasMoreElements())
		{
			long lsec;
			String key=keys.nextElement();
			Vector<String> v=users.get(key);
			lsec=Long.parseLong(v.elementAt(1));
			if(secs-lsec>=90000)
			{
				users.remove(key);
				addChat("",v.elementAt(0)+" has left the room.",lsec+90000);
			}
		}
	}

	/*
	 * updates a user
	 */
	private void updateUser(String name,String sid,long secs,String hostname)
	{
		if(users.get(sid)==null)
		{
			Long nsec=new Long(secs);
			Vector<String> v=new Vector<String>();
			v.add(name);
			v.add(nsec.toString());
			v.add(hostname);
			users.put(sid,v);
			addChat("",name+" has entered.",secs);
		}
		else if(!users.get(sid).elementAt(0).equals(name))
		{
			Vector<String> v=users.get(sid);
			String old=v.remove(0);
			v.add(0,name);
			users.put(sid,v);
			addChat("",old+" is now "+name+".",secs);
		}
	}

	/*
	 * HTTP headers
	 */
	private String getResponseHeader(String type)
	{
		return "HTTP/1.1 200 OK\r\n" +
		       "Server: RezChat Server 1.0\r\n" +
		       "Content-Type: text/"+type+"\r\n" +
		       "Connection: close\r\n" +
		       "Cache-Control: no-cache, must-revalidate\r\n" +
		       "Pragma: no-cache\r\n\r\n";
	}

	private String getErrorHeader()
	{
		return "HTTP/1.1 400 BAD REQUEST\r\n\r\n";
	}

	private String getWaitHeader()
	{
		return "HTTP/1.1 100 CONTINUE\r\n\r\n";
	}

	/*
	 * waits for Chat event, sends XML
	 */
	private String waitForChat(long lmin,String sid) throws Exception
	{
		PrintWriter out=new PrintWriter(s.getOutputStream(),true);
		out.print(getWaitHeader());
		out.flush();
		Random r=new Random();
		int p=((r.nextInt()%6)*1000)+20000;
		synchronized(cond)
		{
			if(lmin>=laction)
				cond.wait(p);
			actionUser(sid,(new Date()).getTime());
		}
		return genChatXML(lmin);
	}

	/*
	 * signals an event to push data out to users
	 */
	private void signalEvent()
	{
		synchronized(cond)
		{
			cond.notifyAll();
		}
	}

	/*
	 * generates XML data for javascript engine
	 */
	private String genChatXML(long lmin)
	{
		String ret;

		synchronized(cond)
		{
			ret=getResponseHeader("xml") +
			    "<ChatXML>" +
			    genNameXML() +
			    parseChatXML(lmin) +
			    "</ChatXML>";
		}

		return ret;
	}

	private String genNameXML()
	{
		String ret;

		ret="<Names>";

		Enumeration<String> keys=users.keys();
		while(keys.hasMoreElements())
		{
			String key=keys.nextElement();
			ret+="<chatname>"+
			      "<name>"+users.get(key).elementAt(0)+"</name>"+
			      "<sid>"+key+"</sid>"+
			      "<hostname>"+users.get(key).elementAt(2)+"</hostname>"+
			      "</chatname>";
		}

		ret+="</Names>";

		return ret;
	}

	private String parseChatXML(long lmin)
	{
		String ret;

		ret="<Chats>";

		for(int i=0;i<chat.size();i++)
		{
			Vector<String> v=chat.elementAt(i);
			long now=Long.parseLong(v.elementAt(0));

			if(lmin>=now)
				continue;

			String date=DateFormat.getDateInstance(DateFormat.SHORT).format(now);
			String time=DateFormat.getTimeInstance(DateFormat.SHORT).format(now);
			String day=DateFormat.getDateInstance(DateFormat.FULL).format(now).split(",")[0];
			String ts=day+" "+time+" ("+date+")";

			ret+="<chat>" +
			     "<id>"+v.elementAt(0)+"</id>" +
			     "<name>"+v.elementAt(1)+"</name>" +
			     "<text>"+v.elementAt(2)+"</text>" +
			     "<ts>"+ts+"</ts>" +
			     "</chat>";
		}

		ret+="</Chats>";

		return ret;
	}

	/*
	 * rez chat html
	 */
	/* RC.START REZCHAT.HTML

	<html>
	<head>
	<title>RezChat</title>
	<base target=_blank>
	<link rel=stylesheet type=text/css href="/code/rezchat.css" /> RT_ICSS
	<script src="/code/rezjax.js"></script> RT_IJS 
	</head>
	<body onload="main();">
	<div id=formdiv class=formstyle>
	<input id=fname name=fname size=10 autocomplete=off>
	<input id=ftext name=ftext size=48 autocomplete=off>&nbsp;
	<input id=sendchat type=button value=Chat onClick="return chatClick();">
	<input id=helpchat type=button value=? onClick="return help();"><br>
	<input id=fjs name=fjs size=60 autocomplete=off> <input id=fcss name=fcss size=60 autocomplete=off> <font size=-1>RezChat 1<a href="http://www.rezsoft.org/rezchat" target=_blank>.</a>0</font><font id=rcmsg size=-2></font>
	<form name=chat action=/ method=POST target=_self>
	<input type=hidden name=js value="RT_JS">
	<input type=hidden name=css value="RT_CSS">
	<input type=hidden name=name value="RT_NAME">
	<input type=hidden name=text value="RT_MSG">
	<input type=hidden name=sid value="RT_SID">
	<input type=hidden name=pw value="RT_PW">
	<input type=hidden name=lmin value="0">
	</form>
	</div>
	<div id=namediv class=namestyle>
	<table id=nametable>
	</table>
	</div>
	<div id=textdiv class=chatstyle>
	<table id=chattable class=chattablestyle>
	<tr><td><font size=-1>&nbsp;Loading...</font></td></tr>
	</table>
	</div>
	</body>
	</html>

	RC.STOP */

	/*
	 * password input html
	 */
	/* RC.START PASSWORD.HTML

	<form action=/ method=POST>
	<br>&nbsp;Password:
	<input type=password name=pw>
	<input type=submit value=Enter>
	</form>

	RC.STOP */

	/*
	 * rez chat style sheet
	 */
	/* RC.START REZCHAT.CSS

	.chatstyle
	{
		position: absolute;
		left: RC_CHATLEFTpx;
		top: RC_CHATDOWNpx;
		height: RC_CHATHEIGHTpx;
		width: RC_CHATSUBWIDTHpx;
		overflow: auto;
		background-color: RC_CHATBACKGROUND;
		scrollbar-3dlight-color: RC_CHATSCROLL;
		scrollbar-base-color: RC_CHATSCROLL;
		scrollbar-darkshadow-color: RC_CHATSCROLL;
	}

	.namestyle
	{
		position: absolute;
		left: RC_CHATNAMELEFTpx;
		top: RC_CHATDOWNpx;
		height: RC_CHATHEIGHTpx;
		width: RC_CHATNAMEWIDTHpx;
		overflow: auto;
		background-color: RC_CHATNAMEBACKGROUND;
		scrollbar-3dlight-color: RC_CHATSCROLL;
		scrollbar-base-color: RC_CHATSCROLL;
		scrollbar-darkshadow-color: RC_CHATSCROLL;
	}

	.formstyle
	{
		position: absolute;
		left: RC_CHATLEFTpx;
		top: RC_FORMHEIGHTpx;
	}

	.chattablestyle
	{
		table-layout: fixed;
		width: 99%;
	}

	td
	{
		overflow: hidden;
	}

	RC.STOP */

	/*
	 * javascript engine
	 */
	/* RC.START REZJAX.JS

	var httpcontent;
	var httpsendchat;
	var titletimer;
	var lastupdate;
	var numreconn;
	var initchat;
	var rce_prechatext=doNothing;
	var rce_postchatext=doNothing;
	var rce_keyext=doNothing;
	var rce_mainext=doNothing;

	function main()
	{
		document.onkeydown=keyhit;
		document.onmouseover=focusHandle;
		httpcontent=getHTTPObject();
		httpsendchat=getHTTPObject();
		titletimer=null;
		lastupdate=0;
		numreconn=0;
		initchat=true;
		getf("sendchat").disabled=true;
		ftext().disabled=true;
		fname().disabled=true;
		try { fname().value=decodeURIComponent(document.chat.name.value); }
		catch(e) { fname().value="Error"; }
		try { ftext().value=decodeURIComponent(document.chat.text.value); }
		catch(e) { ftext().value="Error"; }
		getf("fjs").value=document.chat.js.value;
		getf("fjs").style.display="none";
		getf("fcss").value=document.chat.css.value;
		getf("fcss").style.display="none";
		document.chat.name.value=fname().value;
		document.chat.text.value="";
		hideName();
		updateAllContent();
		rce_mainext();
	}

	function updateAllContent()
	{
		httpcontent.open("POST","pullchat",true);
		updateAllContentGo();
	}

	function updateAllContentWait()
	{
		httpcontent.open("POST","pullchatwait",true);
		updateAllContentGo();
	}

	function updateAllContentGo()
	{
		var postdata="name="+encodeURIComponent(document.chat.name.value)+"&pw="+document.chat.pw.value+"&lmin="+document.chat.lmin.value+"&sid="+document.chat.sid.value;
		httpcontent.onreadystatechange=handleHttpResponseContent;
		try { httpcontent.send(postdata); }
		catch(e) {}
	}

	function handleHttpResponseContent()
	{
		if(httpcontent.readyState==4)
		{
			if(httpcontent.responseText!="" && httpcontent.status==200)
			{
				lastupdate=(new Date()).getTime();
				updateChat(httpcontent.responseXML);
				updateName(httpcontent.responseXML);
				numreconn=0;
				if(initchat)
				{
					initchat=false;
					fname().disabled=false;
					enableText();
				}
				updateAllContentWait();
			}
			else
				checkConnection();
		}
	}

	function updateChat(resp)
	{
		var tbl=getf("chattable");
		var chats=resp.getElementsByTagName("chat");
		var max=document.chat.lmin.value;
		var blink=false;
		var noblink=false;
		var shouldscroll=atBottom();
		if(max==0)
		{
			while(tbl.rows.length>0)
			{
				tbl.deleteRow(0);
			}
			noblink=true;
			shouldscroll=true;
		}
		for(i=0;i<chats.length;i++)
		{
			var lts=chats[i].getElementsByTagName("id")[0].firstChild.nodeValue;
			if(lts<=document.chat.lmin.value)
				continue;
			if(lts>max)
				max=lts;
			if(!chats[i].getElementsByTagName("name")[0].firstChild)
				var name="";
			else
				var name=chats[i].getElementsByTagName("name")[0].firstChild.nodeValue;
			var txt=chats[i].getElementsByTagName("text")[0].firstChild.nodeValue;
			var ts=chats[i].getElementsByTagName("ts")[0].firstChild.nodeValue;
			if(decodeURIComponent(name)!=document.chat.name.value && name!="" || !atBottom())
				blink=true;
			else
				noblink=true;
			if(name=="")
				addChat("<i><font size=-2>"+decodeURIComponent(txt)+"</font></i>",ts);
			else
			{
				var postchat=new Array();
				postchat[0]=decodeURIComponent(name);
				postchat[1]=decodeURIComponent(txt);
				rce_postchatext(postchat);
				if(postchat[1]=="null")
					noblink=true;
				else
					addChat(postchat[0]+": "+postchat[1],ts);
			}
		}
		document.chat.lmin.value=max;
		if(chats.length>0 && shouldscroll)
			scrollDown();
		if(blink && !noblink)
			blinkTitle();
	}

	function updateName(resp)
	{
		var names=resp.getElementsByTagName("chatname");
		var tbl=getf("nametable");
		for(i=0;i<tbl.rows.length;i++)
		{
			for(j=0;j<names.length;j++)
			{
				if(tbl.rows[i].id==names[j].getElementsByTagName("sid")[0].firstChild.nodeValue)
					break;
			}
			if(j==names.length)
			{
				tbl.deleteRow(i);
				i--;
			}
		}
		for(i=0;i<names.length;i++)
		{
			for(j=0;j<tbl.rows.length;j++)
			{
				if(tbl.rows[j].id==names[i].getElementsByTagName("sid")[0].firstChild.nodeValue)
				{
					if(tbl.rows[j].firstChild.innerHTML!=("&nbsp;"+decodeURIComponent(names[i].getElementsByTagName("name")[0].firstChild.nodeValue)))
						tbl.rows[j].firstChild.innerHTML="&nbsp;"+decodeURIComponent(names[i].getElementsByTagName("name")[0].firstChild.nodeValue);
					break;
				}
			}
			if(j==tbl.rows.length)
			{
				tbl.insertRow(j).insertCell(0).innerHTML="&nbsp;"+decodeURIComponent(names[i].getElementsByTagName("name")[0].firstChild.nodeValue);
				tbl.rows[j].id=names[i].getElementsByTagName("sid")[0].firstChild.nodeValue;
				tbl.rows[j].title=names[i].getElementsByTagName("hostname")[0].firstChild.nodeValue;
			}
		}
	}

	function addChat(text,label)
	{
		var tbl=getf("chattable");
		var td=tbl.insertRow(tbl.rows.length).insertCell(0);
		if(label)
			td.title=label;
		td.innerHTML="&nbsp;"+text;
	}

	function checkConnection()
	{
		var current=(new Date()).getTime();
		if(current-lastupdate>300000 || numreconn>6)
			clearContent();
		else
		{
			numreconn++;
			setTimeout("updateAllContent();",15000);
		}
	}

	function clearContent()
	{
		document.chat.lmin.value="0";
		var tbl=getf("chattable");
		while(tbl.rows.length>0)
		{
			tbl.deleteRow(0);
		}
		tbl=getf("nametable");
		while(tbl.rows.length>0)
		{
			tbl.deleteRow(0);
		}
		getf("sendchat").disabled=true;
		ftext().disabled=true;
		fname().disabled=true;
		initchat=true;
		addChat("<input type=button value=Connect onClick=\"updateAllContent();\">","");
	}

	function chatClick()
	{
		hideName();
		var prechat=new Array();
		prechat[0]=fname().value;
		prechat[1]=ftext().value;
		rce_prechatext(prechat);
		if(prechat[1]=="null")
		{
			ftext().value="";
			return;
		}
		document.chat.name.value=prechat[0];
		document.chat.text.value=prechat[1];
		getf("sendchat").disabled=true;
		ftext().disabled=true;
		sendChat();
	}

	function sendChat()
	{
		httpsendchat.open("POST","chat",true);
		var postdata="name="+encodeURIComponent(document.chat.name.value)+"&text="+encodeURIComponent(document.chat.text.value)+"&pw="+document.chat.pw.value+"&lmin="+document.chat.lmin.value+"&sid="+document.chat.sid.value;
		stopBlinkTitle();
		httpsendchat.onreadystatechange=handleHttpResponseSendChat;
		try { httpsendchat.send(postdata); }
		catch(e) { enableText(true); }
	}

	function handleHttpResponseSendChat()
	{
		if(httpsendchat.readyState==4)
		{
			if(httpsendchat.status==200 && httpsendchat.responseText=="OK")
			{
				document.chat.text.value="";
				ftext().value="";
				enableText();
			}
			else
				enableText(true);
		}
	}

	function getHTTPObject()
	{
		if (window.XMLHttpRequest)
			return new XMLHttpRequest();
		else if (window.ActiveXObject)
			return new ActiveXObject("Microsoft.XMLHTTP");
		else
			return null;
	}

	function hideName()
	{
		if(fname().value != "RC_DEFAULTNAME")
		{
			fname().style.display="none";
		}
	}

	function atBottom()
	{
		var top=getf("textdiv").scrollTop;
		var height=getf("textdiv").scrollHeight;
		var aheight=getf("textdiv").clientHeight;
		if(top+aheight==height || height+15<aheight)
			return true;
		else
			return false;
	}

	function scrollDown()
	{
		getf("textdiv").scrollTop=getf("textdiv").scrollHeight;
	}

	function addMessage(msg)
	{
		if(msg=="")
			getf("rcmsg").innerHTML="";
		else
			getf("rcmsg").innerHTML="&nbsp;-&nbsp;"+msg;
	}

	function enableText(error)
	{
		ftext().disabled=false;
		getf("sendchat").disabled=false;
		myFocus(ftext());
		if(error)
			ftext().style.backgroundColor="pink";
		else
			ftext().style.backgroundColor="white";
	}

	function submitToServerCSS()
	{
		document.chat.css.value=getf("fcss").value;
		submitToServer();
	}

	function submitToServerJS()
	{
		document.chat.js.value=getf("fjs").value;
		submitToServer();
	}

	function submitToServer()
	{
		httpcontent.abort();
		document.chat.lmin.value="0";
		document.chat.name.value=fname().value;
		document.chat.text.value=ftext().value;
		document.chat.submit();
	}

	function keyhit(e)
	{
		if(initchat)
			return;
		if(document.all)
			e=window.event;
		var kc=e.keyCode;
		if(kc==13) //enter
		{
			var src=e.srcElement?e.srcElement:e.target;
			if(src==getf("fjs"))
				submitToServerJS();
			else if(src==getf("fcss"))
				submitToServerCSS();
			else
				chatClick();
		}
		else if(e.altKey && kc==78) //alt n
		{
			if(fname().style.display=="none")
			{
				fname().style.display="";
				myFocus(fname());
			}
			else
			{
				fname().style.display="none";
				myFocus(ftext());
			}
		}
		else if(e.altKey && kc==74 && !RC_DEFJSFORCE) //alt j
		{
			if(getf("fjs").style.display=="none")
			{
				getf("fcss").style.display="none";
				getf("fjs").style.display="";
				if(getf("fjs").value=="")
					getf("fjs").value="RC_DEFJSEXT";
				myFocus(getf("fjs"));
			}
			else
			{
				getf("fjs").style.display="none";
				myFocus(ftext());
			}
		}
		else if(e.altKey && kc==67 && !RC_DEFCSSFORCE) //alt c
		{
			if(getf("fcss").style.display=="none")
			{
				getf("fjs").style.display="none";
				getf("fcss").style.display="";
				if(getf("fcss").value=="")
					getf("fcss").value="RC_DEFCSSEXT";
				myFocus(getf("fcss"));
			}
			else
			{
				getf("fcss").style.display="none";
				myFocus(ftext());
			}
		}
		else if(e.altKey && e.shiftKey && kc==82) //alt shift r
			submitToServer();
		else if(e.altKey && kc==82) //alt r
		{
			document.chat.lmin.value="0";
			updateAllContent();
		}
		return rce_keyext(e);
	}

	function myFocus(element)
	{
		if(document.all)
		{
			element.focus();
			element.select();
		}
		else
		{
			element.blur();
			element.focus();
			element.select();
		}
	}

	function focusHandle()
	{
		if(titletimer!=null && atBottom())
			stopBlinkTitle();
	}

	function blinkTitle()
	{
		if(titletimer==null)
		{
			titletimer=setInterval("blinkTitle()",500);
			return;
		}

		if(document.title=="RezChat")
			document.title="REZChat";
		else
			document.title="RezChat";
	}

	function stopBlinkTitle()
	{
		if(titletimer!=null)
			clearInterval(titletimer);
		titletimer=null;
		document.title="RezChat";
	}

	function ftext()
	{
		return document.getElementById("ftext");
	}

	function fname()
	{
		return document.getElementById("fname");
	}

	function getf(f)
	{
		return document.getElementById(f);
	}

	function help()
	{
		var msg="RezChat 1.0 Help\n\nALT+N\t\t-   Change name\t\nALT+R\t\t-   Refresh\nALT+SHIFT+R\t-   Reload\n";
		if(!RC_DEFJSFORCE)
			msg+="ALT+J\t\t-   Add JS Extension\t\n";
		if(!RC_DEFCSSFORCE)
			msg+="ALT+C\t\t-   Add CSS Extension\n";
		msg+="\nFill in your name, type text, then\nhit enter. Welcome to RezChat 1.0!\n";
		alert(msg);
	}

	function doNothing() { }

	RC.STOP */

	/*
	 * loads RC code from source
	 */
	private void loadCode()
	{
		String start="/* rc.start ";
		String end="rc.stop */";
		String line;
		String id="";
		String content="";
		int space=0;
		BufferedReader in;
		boolean js=false;
		code=new Hashtable<String,String>();
		try { in=new BufferedReader(new FileReader(this.getClass().getName()+".java")); }
		catch(Exception ex) { print("EX: loadCode(), open file",ex); return; }

		try
		{
			while((line=in.readLine())!=null)
			{
				if(line.trim().toLowerCase().startsWith(start))
				{
					js=true;
					id=line.trim().toLowerCase().substring(start.length());
					print("Loading: "+id);
					space=line.toLowerCase().indexOf(start);
					content="";
				}
				else if(line.trim().toLowerCase().endsWith(end))
				{
					js=false;
					code.put(id,content);
				}
				else if(js)
				{
					if(space>0 && line.length()>=space && line.substring(0,space).trim().equals(""))
						line=line.substring(space);
					content+=subVars(line,null,"RC_")+"\n";
				}
			}
		}
		catch(Exception ex) { print("EX: loadCode(), read file",ex); }
		try { in.close(); }
		catch(Exception ex) {}
	}

	/*
	 * gets loaded RC code
	 */
	private String getCode(String id) throws Exception
	{
		String content=htget(code,id,"");
		if(content.equals(""))
			throw new Exception(id+" not found");

		content=subVars(content,this,"RT_");

		if(id.endsWith(".js"))
			return getResponseHeader("javascript")+content;
		if(id.endsWith(".css"))
			return getResponseHeader("css")+content;
		else
			return getResponseHeader("html")+content;
	}

	/*
	 * gets local directory request
	 */
	private String getFile(String name) throws Exception
	{
		if(name.indexOf("\\")>=0 || name.indexOf("/")>=0)
			throw new Exception("Invalid path request: "+name);

		try
		{
			char buf[]=new char[1024];
			int ret;
			BufferedReader in=new BufferedReader(new FileReader(name));
			PrintWriter out=new PrintWriter(s.getOutputStream(),true);
			out.print(getResponseHeader("html"));
			while((ret=in.read(buf,0,1024))!=-1)
			{
				out.write(buf,0,ret);
				out.flush();
			}
			in.close();
		}
		catch(Exception e)
		{
			throw new Exception("Invalid file request: "+name);
		}

		return "";
	}

	/*
	 * substitutes variables in loaded code from instance starting with prefix
	 */
	private String subVars(String code,RezChat instance,String prefix)
	{
		if(code.indexOf(prefix)<0)
			return code;
		Field[] flds=this.getClass().getDeclaredFields();
		for(int i=0;i<flds.length;i++)
		{
			String val;
			try { val=flds[i].get(instance).toString(); }
			catch(Exception e) { continue; }
			if(val==null)
				val="";
			if(flds[i].getName().startsWith(prefix))
				code=code.replace(flds[i].getName(),val.toString());
		}
		return code;
	}

	/*
	 * hashtable get wrapper
	 */
	private String htget(Hashtable<String,String> ht, String key, String def)
	{
		return htget(ht,key,def,false);
	}
	
	private String htget(Hashtable<String,String> ht, String key, String def,boolean uncode)
	{
		String ret=ht.get(key);
		if(ret==null || ret.equals(""))
			ret=def;

		if(uncode)
		{
			try { ret=URLDecoder.decode(ret,"UTF-8"); }
			catch(Exception e) { ret=def; }
		}

		return ret;
	}

	/*
	 * load old chat from the log
	 */
	private void loadOldChat()
	{
		String line;
		long ts;
		BufferedReader in;
		chat=new Vector<Vector<String>>();
		try { in=new BufferedReader(new FileReader(FILENAME)); }
		catch(Exception ex) { print("EX: loadOldChat(), open file",ex); return; }

		print("Loading: "+FILENAME);
		try
		{
			while((line=in.readLine())!=null)
			{
				if(line.indexOf("ACTION:chat,")==-1)
					continue;
				String[] lines=line.split(" ");
				if(lines.length!=2)
					continue;
				ts=Long.parseLong(lines[0]);
				String paramstr=lines[1].substring(7);
				String[] params=paramstr.split(",");
				if(params.length!=2)
					continue;
				service(genHT("load",params[1]),ts);
			}
		}
		catch(Exception ex) { print("EX: loadOldChat(), read file",ex); }

		try { in.close(); }
		catch(Exception ex) {}
	}

	/*
	 * debug print to a file
	 */
	public static void print(String str)
	{
		print(str,null);
	}

	public static void print(String str,Exception ex)
	{
		try
		{
			Date now=new Date();
			String ts=now.getTime()+"";
			PrintWriter pw=new PrintWriter(new BufferedWriter(new FileWriter(FILENAME,true)));
			pw.println(ts+" "+str);
			if(ex!=null)
				ex.printStackTrace(pw);
			pw.close();
		}
		catch(Exception exn) {}
	}
}

