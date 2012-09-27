package org.mctourney.AutoReferee;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang.StringUtils;

import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.material.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

import org.mctourney.AutoReferee.AutoRefTeam.WinCondition;
import org.mctourney.AutoReferee.listeners.RefereeChannelListener;
import org.mctourney.AutoReferee.listeners.ZoneListener;
import org.mctourney.AutoReferee.util.ArmorPoints;
import org.mctourney.AutoReferee.util.BlockData;
import org.mctourney.AutoReferee.util.CuboidRegion;
import org.mctourney.AutoReferee.util.Vector3;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class AutoRefMatch
{
	// online map list
	private static String MAPREPO = "http://s3.amazonaws.com/autoreferee/maps/";
	
	public static String getMapRepo()
	{ return MAPREPO; }
	
	// set new map repo
	public static void changeMapRepo(String s)
	{ MAPREPO = s + "/"; }

	// local storage locations
	private static File matchSummaryDirectory = null;
	static
	{
		// determine the location of the match-summary directory
		FileConfiguration config = AutoReferee.getInstance().getConfig();
		if (config.isString("local-storage.match-summary"))
			matchSummaryDirectory = new File(config.getString("local-storage.match-summary"));
		else matchSummaryDirectory = new File(AutoReferee.getInstance().getDataFolder(), "summary");

		// if the folder doesnt exist, create it...
		if (!matchSummaryDirectory.exists()) matchSummaryDirectory.mkdir();
	}
	
	// world this match is taking place on
	private World world;
	public Location worldSpawn = null;
	
	private void setWorld(World w)
	{
		world = w;
		
		worldSpawn = world.getSpawnLocation();
		while (world.getBlockTypeIdAt(worldSpawn) != Material.AIR.getId())
			worldSpawn = worldSpawn.add(0, 1, 0);
	}

	public World getWorld()
	{ return world; }
	
	public Location getWorldSpawn()
	{ return worldSpawn; }
	
	private boolean tmp;

	private boolean isTemporaryWorld()
	{ return tmp; }

	// time to set the world to at the start of the match
	private long startTime = 8000L;
	
	public long getStartTime()
	{ return startTime; }

	public void setStartTime(long startTime)
	{ this.startTime = startTime; }
	
	public enum MatchStatus
	{
		NONE, WAITING, READY, PLAYING, COMPLETED;

		public boolean isBeforeMatch()
		{ return this.ordinal() < PLAYING.ordinal() && this != NONE; }

		public boolean isAfterMatch()
		{ return this.ordinal() > PLAYING.ordinal() && this != NONE; }

		public boolean inProgress()
		{ return this == PLAYING; }
	}
	
	// status of the match
	private MatchStatus currentState = MatchStatus.NONE;

	public MatchStatus getCurrentState()
	{ return currentState; }

	public void setCurrentState(MatchStatus s)
	{ this.currentState = s; this.setupSpectators(); }
	
	// teams participating in the match
	private Set<AutoRefTeam> teams = null;

	public Set<AutoRefTeam> getTeams()
	{ return teams; }

	public List<AutoRefTeam> getSortedTeams()
	{
		List<AutoRefTeam> sortedTeams = Lists.newArrayList(getTeams());
		Collections.sort(sortedTeams);
		return sortedTeams;
	}

	public String getTeamList()
	{
		Set<String> tlist = Sets.newHashSet();
		for (AutoRefTeam team : getSortedTeams())
			tlist.add(team.getName());
		return StringUtils.join(tlist, ", ");
	}
	
	private AutoRefTeam winningTeam = null;
	
	public AutoRefTeam getWinningTeam()
	{ return winningTeam; }
	
	public void setWinningTeam(AutoRefTeam t)
	{ winningTeam = t; }
	
	// region defined as the "start" region (safe zone)
	private CuboidRegion startRegion = null;

	public CuboidRegion getStartRegion()
	{ return startRegion; }

	public void setStartRegion(CuboidRegion startRegion)
	{ this.startRegion = startRegion; }
	
	// name of the match
	private String matchName = null;
	
	public void setMatchName(String nm)
	{ matchName = nm; }

	public String getMatchName()
	{
		// if we have a specific match name...
		if (matchName != null) return matchName;
		
		// generate a date string
		String date = new SimpleDateFormat("dd MMM yyyy").format(new Date());
		
		// if the map is named, return map name as a placeholder
		if (mapName != null) return mapName + ": " + date;
		
		// otherwise, just return the date
		return date;
	}
	
	// configuration information for the world
	public File worldConfigFile;
	public FileConfiguration worldConfig;
	
	// basic variables loaded from file
	private String mapName = null;
	private Collection<String> mapAuthors = null;

	public String getMapName() 
	{ return mapName; }
	
	private String versionString = "1.0";
	
	public String getVersion()
	{ return versionString; }

	public String getMapAuthors()
	{
		if (mapAuthors != null && mapAuthors.size() != 0)
			return StringUtils.join(mapAuthors, ", ");
		return "??";
	}
	
	private long startTicks = 0;
	
	public long getStartTicks()
	{ return startTicks; }

	public void setStartTicks(long startTicks)
	{ this.startTicks = startTicks; }

	public long getMatchTime()
	{
		if (!getCurrentState().inProgress()) return 0L;
		return (getWorld().getFullTime() - getStartTicks()) / 20L;
	}

	private long timeLimit = -1L;

	public long getTimeLimit()
	{ return timeLimit; }

	public boolean hasTimeLimit()
	{ return timeLimit != -1L; }

	public long getTimeRemaining()
	{ return timeLimit - getMatchTime(); }

	public void setTimeLimit(long limit)
	{ this.timeLimit = limit; }

	public String getTimestamp()
	{ return getTimestamp(":"); }

	public String getTimestamp(String sep)
	{
		long timestamp = this.getMatchTime();
		return String.format("%02d%s%02d%s%02d", timestamp/3600L,
			sep, (timestamp/60L)%60L, sep, timestamp%60L);
	}

	// task that starts the match
	public AutoRefMatch.CountdownTask matchStarter = null;
	
	// mechanisms to open the starting gates
	public Set<StartMechanism> startMechanisms = null;
	
	// protected entities - only protected from "butchering"
	public Set<UUID> protectedEntities = null;
	
	public boolean allowFriendlyFire = false;
	public boolean allowCraft = false;

	// provided by configuration file
	public static boolean allowTies = false;
	
	// list of items players may not craft
	private Set<BlockData> prohibitCraft = Sets.newHashSet();
	
	// range of inexact placement
	private int inexactRange = 2;
	
	public int getInexactRange()
	{ return inexactRange; }

	// transcript of every event in the match
	private List<TranscriptEvent> transcript;
	
	private boolean refereeReady = false;
	
	public boolean isRefereeReady()
	{ return getReferees().size() == 0 || refereeReady; }

	public void setRefereeReady(boolean r)
	{ refereeReady = r; }

	private CommandSender debugRecipient = null;

	public boolean isDebugMode()
	{ return debugRecipient != null; }

	public void debug(String msg)
	{ if (debugRecipient != null) debugRecipient.sendMessage(msg); }
	
	public void setDebug(CommandSender recp)
	{
		if (recp != null && recp.hasPermission("autoreferee.streamer"))
			AutoReferee.getInstance().getLogger().info(
				"You may not direct debug message to a streamer!");
			
		debugRecipient = recp;
		debug(ChatColor.GREEN + "Debug mode is now " + 
			(isDebugMode() ? "on" : "off"));
	}
	
	// number of seconds for each phase
	public static final int READY_SECONDS = 15;
	public static final int COMPLETED_SECONDS = 180;
	
	private int customReadyDelay = -1;
	
	public int getReadyDelay()
	{
		if (customReadyDelay >= 0) return customReadyDelay;
		return AutoReferee.getInstance().getConfig().getInt(
			"delay-seconds.ready", AutoRefMatch.READY_SECONDS);
	}
	
	public void setReadyDelay(int delay)
	{ this.customReadyDelay = delay; }
	
	public AutoRefMatch(World world, boolean tmp, MatchStatus state)
	{ this(world, tmp); setCurrentState(state); }

	public AutoRefMatch(World world, boolean tmp)
	{
		setWorld(world);
		loadWorldConfiguration();
		
		// is this world a temporary world?
		this.tmp = tmp;
		
		// brand new match transcript
		transcript = Lists.newLinkedList();
		
		// fix vanish
		this.setupSpectators();
	}

	public Set<AutoRefPlayer> getPlayers()
	{
		Set<AutoRefPlayer> players = Sets.newHashSet();
		for (AutoRefTeam team : teams)
			players.addAll(team.getPlayers());
		return players;
	}

	public Set<Player> getReferees()
	{ return getReferees(true); }

	public Set<Player> getReferees(boolean excludeStreamers)
	{
		Set<Player> refs = Sets.newHashSet();
		for (Player p : world.getPlayers())
			if (p.hasPermission("autoreferee.referee") && !isPlayer(p))
				if (!excludeStreamers || !p.hasPermission("autoreferee.streamer")) refs.add(p);
		return refs;
	}

	public boolean isReferee(Player p)
	{
		// pretty much the only reason we would send a null player
		// is if we are checking the ConsoleCommandSender
		if (p == null) return true;

		for (AutoRefPlayer apl : getPlayers())
			if (apl.getPlayerName() == p.getName()) return false;
		return p.hasPermission("autoreferee.referee");
	}
	
	public static boolean isCompatible(World w)
	{ return new File(w.getWorldFolder(), "autoreferee.yml").exists(); }
	
	public void reload()
	{ this.loadWorldConfiguration(); }
	
	@SuppressWarnings("unchecked")
	private void loadWorldConfiguration()
	{
		// file stream and configuration object (located in world folder)
		worldConfigFile = new File(world.getWorldFolder(), "autoreferee.yml");
		worldConfig = YamlConfiguration.loadConfiguration(worldConfigFile);

		// load up our default values file, so that we can have a base to work with
		InputStream defConfigStream = AutoReferee.getInstance().getResource("defaults/map.yml");
		if (defConfigStream != null) worldConfig.setDefaults(
			YamlConfiguration.loadConfiguration(defConfigStream));

		// make sure any defaults get copied into the map file
		worldConfig.options().copyDefaults(true);
		worldConfig.options().header(AutoReferee.getInstance().getDescription().getFullName());
		worldConfig.options().copyHeader(false);

		teams = Sets.newHashSet();
		messageReferees("match", getWorld().getName(), "init");
		setCurrentState(MatchStatus.WAITING);

		for (Map<?, ?> map : worldConfig.getMapList("match.teams"))
			teams.add(AutoRefTeam.fromMap((Map<String, Object>) map, this));
		
		startMechanisms = Sets.newHashSet();
		for (String sm : worldConfig.getStringList("match.start-mechanisms"))
			startMechanisms.add(StartMechanism.unserialize(world, sm));
		
		protectedEntities = Sets.newHashSet();
		for (String uid : worldConfig.getStringList("match.protected-entities"))
			protectedEntities.add(UUID.fromString(uid));
		
		prohibitCraft = Sets.newHashSet();
		for (String b : worldConfig.getStringList("match.no-craft"))
			prohibitCraft.add(BlockData.fromString(b));
		
		// HELPER: ensure all protected entities are still present in world
		Set<UUID> uuidSearch = Sets.newHashSet(protectedEntities);
		for (Entity e : getWorld().getEntities()) uuidSearch.remove(e.getUniqueId());
		if (!uuidSearch.isEmpty()) this.broadcast(ChatColor.RED + "" + ChatColor.BOLD + "WARNING: " + 
			ChatColor.RESET + "One or more protected entities are missing!");
		
		// get the start region (safe for both teams, no pvp allowed)
		if (worldConfig.isString("match.start-region"))
			startRegion = CuboidRegion.fromCoords(worldConfig.getString("match.start-region"));
		
		// get the time the match is set to start
		if (worldConfig.isString("match.start-time"))
			startTime = AutoReferee.parseTimeString(worldConfig.getString("match.start-time"));
		
		// get the extra settings cached
		mapName = worldConfig.getString("map.name", "<Untitled>");
		versionString = worldConfig.getString("map.version", "1.0");
		mapAuthors = worldConfig.getStringList("map.creators");
		
		allowFriendlyFire = worldConfig.getBoolean("match.allow-ff", false);
		allowCraft = worldConfig.getBoolean("match.allow-craft", false);

		// attempt to set world difficulty as best as possible
		String diff = worldConfig.getString("match.difficulty", "HARD");
		world.setDifficulty(getDifficulty(diff));

		// restore competitive settings and some default values
		world.setPVP(true);
		world.setSpawnFlags(true, true);

		world.setTicksPerAnimalSpawns(-1);
		world.setTicksPerMonsterSpawns(-1);
		
		// last, send an update about the match to everyone logged in
		for (Player pl : world.getPlayers()) sendMatchInfo(pl);
	}

	private static Difficulty getDifficulty(String d)
	{
		Difficulty diff = Difficulty.valueOf(d.toUpperCase());
		try { diff = Difficulty.getByValue(Integer.parseInt(d)); }
		catch (NumberFormatException e) {  }

		return diff;
	}

	public void saveWorldConfiguration() 
	{
		// if there is no configuration object or file, nothin' doin'...
		if (worldConfigFile == null || worldConfig == null) return;

		// create and save the team data list
		List<Map<String, Object>> teamData = Lists.newArrayList();
		for (AutoRefTeam t : teams) teamData.add(t.toMap());
		worldConfig.set("match.teams", teamData);
		
		// save the start mechanisms
		List<String> smList = Lists.newArrayList();
		for ( StartMechanism sm : startMechanisms ) smList.add(sm.serialize());
		worldConfig.set("match.start-mechanisms", smList);
		
		// save the protected entities
		List<String> peList = Lists.newArrayList();
		for ( UUID uid : protectedEntities ) peList.add(uid.toString());
		worldConfig.set("match.protected-entities", peList);
		
		// save the craft blacklist
		List<String> ncList = Lists.newArrayList();
		for ( BlockData bd : prohibitCraft ) ncList.add(bd.toString());
		worldConfig.set("match.no-craft", ncList);
		
		// save the start region
		if (startRegion != null)
			worldConfig.set("match.start-region", startRegion.toCoords());

		// save the configuration file back to the original filename
		try { worldConfig.save(worldConfigFile); }

		// log errors, report which world did not save
		catch (java.io.IOException e)
		{ AutoReferee.getInstance().getLogger().info("Could not save world config: " + world.getName()); }
	}

	public void messageReferees(String ...parts)
	{ for (Player ref : getReferees(false)) messageReferee(ref, parts); }	
	
	public void messageReferee(Player ref, String ...parts)
	{
		try
		{
			String msg = StringUtils.join(parts, RefereeChannelListener.DELIMITER);
			ref.sendPluginMessage(AutoReferee.getInstance(), AutoReferee.REFEREE_PLUGIN_CHANNEL, 
				msg.getBytes(AutoReferee.PLUGIN_CHANNEL_ENC));
		}
		catch (UnsupportedEncodingException e)
		{ AutoReferee.getInstance().getLogger().info("Unsupported encoding: " + AutoReferee.PLUGIN_CHANNEL_ENC); }
	}

	public void updateReferee(Player ref)
	{
		messageReferee(ref, "match", getWorld().getName(), "init");
		messageReferee(ref, "match", getWorld().getName(), "map", getMapName());

		if (getCurrentState().inProgress())
			messageReferee(ref, "match", getWorld().getName(), "time", getTimestamp(","));
		
		for (AutoRefTeam team : getTeams())
		{
			messageReferee(ref, "team", team.getRawName(), "init");
			messageReferee(ref, "team", team.getRawName(), "color", team.getColor().toString());

			for (BlockData bd : team.getObjectives())
			{
				messageReferee(ref, "team", team.getRawName(), "obj", "+" + bd.toString());
				messageReferee(ref, "team", team.getRawName(), "state", bd.toString(), 
					team.getObjectiveStatus(bd).toString());
			}

			for (AutoRefPlayer apl : team.getPlayers())
			{
				messageReferee(ref, "team", team.getRawName(), "player", "+" + apl.getPlayerName());
				updateRefereePlayerInfo(ref, apl);
			}
		}
	}

	private void updateRefereePlayerInfo(Player ref, AutoRefPlayer apl)
	{
		messageReferee(ref, "player", apl.getPlayerName(), "kills", Integer.toString(apl.totalKills));
		messageReferee(ref, "player", apl.getPlayerName(), "deaths", Integer.toString(apl.totalDeaths));
		messageReferee(ref, "player", apl.getPlayerName(), "streak", Integer.toString(apl.totalStreak));
		apl.sendAccuracyUpdate(ref);

		Player pl = apl.getPlayer();
		if (pl != null)
		{
			messageReferee(ref, "player", apl.getPlayerName(), "hp", Integer.toString(pl.getHealth()));
			messageReferee(ref, "player", apl.getPlayerName(), "armor", Integer.toString(ArmorPoints.fromPlayer(pl)));
		}

		for (AutoRefPlayer en : getPlayers()) if (apl.isDominating(en))
			messageReferee(ref, "player", apl.getPlayerName(), "dominate", en.getPlayerName());
	}

	public void broadcast(String msg)
	{ for (Player p : world.getPlayers()) p.sendMessage(msg); }

	public static String normalizeMapName(String m)
	{ return m == null ? null : m.replaceAll("[^0-9a-zA-Z]+", ""); }

	public String getVersionString()
	{ return String.format("%s-v%s", this.getMapName().replaceAll("[^0-9a-zA-Z]+", ""), this.getVersion()); }

	public static void setupWorld(World w, boolean b)
	{
		// if this map isn't compatible with AutoReferee, quit...
		if (AutoReferee.getInstance().getMatch(w) != null || !isCompatible(w)) return;
		AutoReferee.getInstance().addMatch(new AutoRefMatch(w, b, MatchStatus.WAITING));
	}

	public File archiveMapData() throws IOException
	{
		// make sure the folder exists first
		File archiveFolder = new File(AutoRefMap.getMapLibrary(), this.getVersionString());
		if (!archiveFolder.exists()) archiveFolder.mkdir();
		
		// (1) copy the configuration file:
		FileUtils.copyFileToDirectory(
			new File(getWorld().getWorldFolder(), AutoReferee.CFG_FILENAME), archiveFolder);
		
		// (2) copy the level.dat:
		FileUtils.copyFileToDirectory(
			new File(getWorld().getWorldFolder(), "level.dat"), archiveFolder);
		
		// (3) copy the region folder (only the .mca files):
		FileUtils.copyDirectory(new File(getWorld().getWorldFolder(), "region"), 
			new File(archiveFolder, "region"), FileFilterUtils.suffixFileFilter(".mca"));
		
		// (4) make an empty data folder:
		new File(archiveFolder, "data").mkdir();
		return archiveFolder;
	}
	
	private static void addToZip(ZipOutputStream zip, File f, File base) throws IOException
	{
		zip.putNextEntry(new ZipEntry(base.toURI().relativize(f.toURI()).getPath()));
		if (f.isDirectory()) for (File c : f.listFiles()) addToZip(zip, c, base);
		else IOUtils.copy(new FileInputStream(f), zip);
	}
	
	public File distributeMap() throws IOException
	{
		File archiveFolder = this.archiveMapData();
		File outZipfile = new File(AutoRefMap.getMapLibrary(), this.getVersionString() + ".zip");
		
		ZipOutputStream zip = new ZipOutputStream(new 
			BufferedOutputStream(new FileOutputStream(outZipfile)));
		zip.setMethod(ZipOutputStream.DEFLATED);
		addToZip(zip, archiveFolder, AutoRefMap.getMapLibrary());
		
		zip.close();
		return archiveFolder;
	}
	
	public class WorldFolderDeleter implements Runnable
	{
		private File worldFolder;
		public int task = -1;
		
		WorldFolderDeleter(World w)
		{ this.worldFolder = w.getWorldFolder(); }
		
		@Override
		public void run()
		{
			AutoReferee autoref = AutoReferee.getInstance();
			try
			{
				// if we fail, we loop back around again on the next try...
				FileUtils.deleteDirectory(worldFolder);
				
				// otherwise, stop the repeating task
				autoref.getLogger().info(worldFolder.getName() + " deleted!");
				autoref.getServer().getScheduler().cancelTask(task);
			}
			catch (IOException e)
			{ autoref.getLogger().info("File lock held on " + worldFolder.getName()); }
		}
	}

	public void destroy()
	{
		AutoReferee autoref = AutoReferee.getInstance();
		
		// first, handle all the players
		for (Player p : world.getPlayers()) autoref.playerDone(p);
					
		// if everyone has been moved out of this world, clean it up
		if (world.getPlayers().size() == 0)
		{
			// if we are running in auto-mode and this is OUR world
			if (autoref.isAutoMode() || this.isTemporaryWorld())
			{
				// only change the state if we are sure we are going to unload
				this.setCurrentState(MatchStatus.NONE);
				autoref.clearMatch(this);
				
				autoref.getServer().unloadWorld(world, false);
				if (!autoref.getConfig().getBoolean("save-worlds", false))
				{
					WorldFolderDeleter wfd = new WorldFolderDeleter(world);
					wfd.task = autoref.getServer().getScheduler()
						.scheduleSyncRepeatingTask(autoref, wfd, 0L, 10 * 20L);
				}
			}
		}
	}
	
	public boolean canCraft(BlockData bd)
	{
		for (BlockData nc : prohibitCraft)
			if (nc.equals(bd)) return false;
		return true;
	}

	public void addIllegalCraft(BlockData bd)
	{
		this.prohibitCraft.add(bd);
		this.broadcast("Crafting " + bd.getName() + " is now prohibited");
	}

	public AutoRefTeam getArbitraryTeam()
	{
		// minimum size of any one team, and an array to hold valid teams
		int minsize = Integer.MAX_VALUE;
		List<AutoRefTeam> vteams = Lists.newArrayList();
		
		// determine the size of the smallest team
		for (AutoRefTeam team : getTeams())
			if (team.getPlayers().size() < minsize)
				minsize = team.getPlayers().size();
	
		// make a list of all teams with this size
		for (AutoRefTeam team : getTeams())
			if (team.getPlayers().size() == minsize) vteams.add(team);
	
		// return a random element from this list
		return vteams.get(new Random().nextInt(vteams.size()));
	}

	public static class StartMechanism
	{
		public Location loc = null;
		public BlockState blockState = null;
		public boolean state = true;
		
		public StartMechanism(Block block, boolean state)
		{
			this.state = state;
			this.loc = block.getLocation(); 
			this.blockState = block.getState();
		}
		
		public StartMechanism(Block block)
		{ this(block, true); }
		
		@Override public int hashCode()
		{ return loc.hashCode() ^ blockState.hashCode(); }
		
		@Override public boolean equals(Object o)
		{ return (o instanceof StartMechanism) && hashCode() == o.hashCode(); }
		
		public String serialize()
		{ return Vector3.fromLocation(loc).toCoords() + ":" + Boolean.toString(state); }
		
		public static StartMechanism unserialize(World w, String sm)
		{
			String[] p = sm.split(":");

			Block block = w.getBlockAt(Vector3.fromCoords(p[0]).toLocation(w));
			boolean state = Boolean.parseBoolean(p[1]);

			return new StartMechanism(block, state);
		}
		
		@Override public String toString()
		{ return blockState.getType().name() + "(" + Vector3.fromLocation(loc).toCoords() + 
			"):" + Boolean.toString(state); }
	}

	public StartMechanism addStartMech(Block block, boolean state)
	{
		if (block.getType() != Material.LEVER) state = true;
		StartMechanism sm = new StartMechanism(block, state);
		startMechanisms.add(sm);
		
		AutoReferee.getInstance().getLogger().info(
			sm.toString() + " is a start mechanism.");
		return sm;
	}

	public boolean isStartMechanism(Location loc)
	{
		if (loc == null) return false;
		for (StartMechanism sm : startMechanisms)
			if (loc.equals(sm.loc)) return true;
		return false;
	}
	
	// unserialized match initialization parameters
	static class MatchParams
	{
		public static class TeamInfo
		{
			private String name;
			
			public String getName()
			{ return name; }
			
			private List<String> players;
	
			public List<String> getPlayers()
			{ return Collections.unmodifiableList(players); }
		}
		
		// info about all the teams
		private List<TeamInfo> teams;
		
		public List<TeamInfo> getTeams()
		{ return Collections.unmodifiableList(teams); }
	
		// match tag for reporting
		private String tag;
		
		public String getTag()
		{ return tag; }
		
		// map name
		private String map;
		
		public String getMap()
		{ return map; }
	}

	public void start()
	{
		// set up the world time one last time
		world.setTime(startTime);
		startTicks = world.getFullTime();
		
		addEvent(new TranscriptEvent(this, TranscriptEvent.EventType.MATCH_START,
			"Match began.", null, null, null));

		// send referees the start event
		messageReferees("match", getWorld().getName(), "start");
		
		// remove all mobs, animals, and items (again)
		this.clearEntities();

		// loop through all the redstone mechanisms required to start / FIXME BUKKIT-1858
		if (AutoReferee.getInstance().isAutoMode() || AutoReferee.hasSportBukkitApi())
			for (StartMechanism sm : startMechanisms)
		{
			MaterialData mdata = sm.blockState.getData();
			switch (sm.blockState.getType())
			{
				case LEVER:
					// flip the lever to the correct state
					((Lever) mdata).setPowered(sm.state);
					break;
					
				case STONE_BUTTON:
					// press (or depress) the button
					((Button) mdata).setPowered(sm.state);
					break;
					
				case WOOD_PLATE:
				case STONE_PLATE:
					// press (or depress) the pressure plate
					((PressurePlate) mdata).setData((byte)(sm.state ? 0x1 : 0x0));
					break;
			}
			
			// save the block state and fire an update
			sm.blockState.setData(mdata);
			sm.blockState.update(true);
		}
		
		// set teams as started
		for (AutoRefTeam team : getTeams())
			team.startMatch();
			
		// set the current state to playing
		setCurrentState(MatchStatus.PLAYING);

		// match minute timer
		Plugin plugin = AutoReferee.getInstance();
		clockTask = new MatchClockTask();

		clockTask.task = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
			plugin, clockTask, 60 * 20L, 60 * 20L);
	}

	private static final Set<Long> announceMinutes = 
		Sets.newHashSet(60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L);

	// handle to the clock task
	private MatchClockTask clockTask = null;

	public void cancelClock()
	{
		if (clockTask != null && clockTask.task != -1) AutoReferee.getInstance()
			.getServer().getScheduler().cancelTask(clockTask.task);
		clockTask = null;
	}

	public class MatchClockTask implements Runnable
	{
		public int task;

		public void run()
		{
			AutoRefMatch match = AutoRefMatch.this;
			long minutesRemaining = match.getTimeRemaining() / 60L;

			// drop out just in case
			if (!match.hasTimeLimit()) return;

			if (minutesRemaining == 0L)
			{
				String timelimit = (match.getTimeLimit() / 60L) + " min";
				match.addEvent(new TranscriptEvent(match, TranscriptEvent.EventType.MATCH_END,
					"Match time limit reached: " + timelimit, null, null, null));
				match.matchComplete();
			}
			else if (AutoRefMatch.announceMinutes.contains(minutesRemaining))
				match.broadcast(">>> " + ChatColor.GREEN + 
					"Match ends in " + minutesRemaining + "m");
		}
	}

	private int getVanishLevel(Player p)
	{
		// if this person is a player, lowest vanish level
		if (isPlayer(p)) return 0;

		// streamers are ONLY able to see streamers and players
		if (p.hasPermission("autoreferee.streamer")) return 1;
		
		// referees have the highest vanish level (see everything)
		if (p.hasPermission("autoreferee.referee")) return 200;
		
		// spectators can only be seen by referees
		return 100;
	}
	
	// either vanish or show the player `subj` from perspective of `view`
	private void setupVanish(Player view, Player subj)
	{
		if (getVanishLevel(view) >= getVanishLevel(subj) || 
			!this.getCurrentState().inProgress()) view.showPlayer(subj);
		else view.hidePlayer(subj);
	}

	public void setupSpectators(Player focus)
	{
		if (getCurrentState().isBeforeMatch()) setSpectatorMode(focus, isReferee(focus));
		else setSpectatorMode(focus, !isPlayer(focus) || getCurrentState().isAfterMatch());
		
		for ( Player pl : getWorld().getPlayers() )
		{
			// setup vanish in both directions
			setupVanish(focus, pl);
			setupVanish(pl, focus);
		}
	}

	public void setSpectatorMode(Player p, boolean b)
	{ setSpectatorMode(p, b, b ? GameMode.CREATIVE : GameMode.SURVIVAL); }

	public void setSpectatorMode(Player p, boolean b, GameMode gm)
	{
		p.setGameMode(gm);
		AutoReferee.setAffectsSpawning(p, !b);
		
		boolean noEntityCollide = b && getCurrentState().inProgress();
		AutoReferee.setCollidesWithEntities(p, !noEntityCollide);
	}

	public void setupSpectators()
	{ for ( Player pl : getWorld().getPlayers() ) setupSpectators(pl); }
	
	public void clearEntities()
	{
		for (Entity e : world.getEntitiesByClasses(Monster.class, 
			Animals.class, Item.class, ExperienceOrb.class, Arrow.class))
			if (!protectedEntities.contains(e.getUniqueId())) e.remove();
	}

	public boolean isCountdownRunning()
	{ return matchStarter != null && matchStarter.task != -1; }

	public void cancelCountdown()
	{
		if (isCountdownRunning())
			AutoReferee.getInstance().getServer().getScheduler().cancelTask(matchStarter.task);
		matchStarter = null;
	}

	// helper class for starting match, synchronous task
	static class CountdownTask implements Runnable
	{
		public static final ChatColor COLOR = ChatColor.GREEN;
		
		public int task = -1;
		private int remainingSeconds = 3;
		
		private AutoRefMatch match = null;
		private boolean start = false;
		
		public CountdownTask(AutoRefMatch m, int time, boolean start)
		{
			match = m;
			remainingSeconds = time;
			this.start = start;
		}
		
		public void run()
		{
			if (remainingSeconds > 3)
			{
				// currently nothing...
			}

			// if the countdown has ended...
			else if (remainingSeconds == 0)
			{
				// setup world to go!
				if (this.start) match.start();
				match.broadcast(">>> " + CountdownTask.COLOR + "GO!");
				
				// cancel the task
				match.cancelCountdown();
			}
			
			// report number of seconds remaining
			else match.broadcast(">>> " + CountdownTask.COLOR + 
				Integer.toString(remainingSeconds) + "...");

			// count down
			--remainingSeconds;
		}

		public int getRemainingSeconds()
		{ return remainingSeconds; }
	}

	// prepare this world to start
	private void prepareMatch()
	{
		// nothing to do if the countdown is running
		if (isCountdownRunning()) return;

		// set the current time to the start time
		world.setTime(this.startTime);
		
		// remove all mobs, animals, and items
		this.clearEntities();
		
		// turn off weather forever (or for a long time)
		world.setStorm(false);
		world.setWeatherDuration(Integer.MAX_VALUE);
		
		// prepare all players for the match
		for (AutoRefPlayer apl : getPlayers()) apl.heal();

		int readyDelay = this.getReadyDelay();
		
		// announce the match starting in X seconds
		this.broadcast(CountdownTask.COLOR + "Match will begin in "
			+ ChatColor.WHITE + Integer.toString(readyDelay) + CountdownTask.COLOR + " seconds.");

		// send referees countdown notification
		messageReferees("match", getWorld().getName(), "countdown", Integer.toString(readyDelay));
		startCountdown(readyDelay, true);
	}

	public void startCountdown(int readyDelay, boolean startMatch)
	{
		// get a copy of the bukkit scheduling daemon
		BukkitScheduler scheduler = AutoReferee.getInstance().getServer().getScheduler();
		
		// cancel any previous match-start task
		if (this.matchStarter != null && this.matchStarter.task != -1)
			scheduler.cancelTask(this.matchStarter.task);
		
		// schedule the task to announce and prepare the match
		this.matchStarter = new CountdownTask(this, readyDelay, startMatch);
		this.matchStarter.task = scheduler.scheduleSyncRepeatingTask(
				AutoReferee.getInstance(), this.matchStarter, 0L, 20L);
	}

	public void checkTeamsReady() 
	{
		// this function is only useful if called prior to the match
		if (!getCurrentState().isBeforeMatch()) return;
		
		// if there are no players on the server
		if (getPlayers().size() == 0)
		{
			// set all the teams to not ready and status as waiting
			for ( AutoRefTeam t : teams ) t.setReady(false);
			setCurrentState(MatchStatus.WAITING); return;
		}
		
		// if we aren't in online mode, assume we are always ready
		if (!AutoReferee.getInstance().isAutoMode())
		{ setCurrentState(MatchStatus.READY); return; }
		
		// check if all the players are here
		boolean ready = true;
		for ( OfflinePlayer opl : getExpectedPlayers() )
			ready &= opl.isOnline() && isPlayer(opl.getPlayer()) &&
				getPlayer(opl.getPlayer()).isReady();
		
		// set status based on whether the players are online
		setCurrentState(ready ? MatchStatus.READY : MatchStatus.WAITING);
	}
	
	public void checkTeamsStart()
	{
		boolean teamsReady = true;
		for ( AutoRefTeam t : teams )
			teamsReady &= t.isReady() || t.isEmptyTeam();
		
		boolean ready = getReferees().size() == 0 ? teamsReady : isRefereeReady();
		if (teamsReady && !ready) for (Player p : getReferees())
			p.sendMessage(ChatColor.GRAY + "Teams are ready. Type /ready to begin the match.");
		
		// everyone is ready, let's go!
		if (ready) this.prepareMatch();
	}
	
	public Location blockInRange(BlockData bd, Location loc, int r)
	{
		Block b = getWorld().getBlockAt(loc);
		for (int x = -r; x <= r; ++x)
		for (int y = -r; y <= r; ++y)
		for (int z = -r; z <= r; ++z)
		{
			Block rel = b.getRelative(x, y, z);
			if (bd.matches(rel)) return rel.getLocation();
		}
					
		return null;
	}

	private Location blockInRange(WinCondition wc)
	{ return blockInRange(wc.getBlockData(), wc.getLocation(), wc.getInexactRange()); }
	
	public void checkWinConditions()
	{
		Plugin plugin = AutoReferee.getInstance();
		plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin,
			new Runnable(){ public void run(){ delayedCheckWinConditions(); } });
	}
	
	public void delayedCheckWinConditions()
	{
		// this code is only called in BlockPlaceEvent and BlockBreakEvent when
		// we have confirmed that the state is PLAYING, so we know we are definitely
		// in a match if this function is being called
		
		if (getCurrentState().inProgress()) for (AutoRefTeam team : this.teams)
		{
			// if there are no win conditions set, skip this team
			if (team.winConditions.size() == 0) continue;
			
			// check all win condition blocks (AND together)
			boolean win = true;
			for (WinCondition wc : team.winConditions)
			{
				Location placedLoc = blockInRange(wc);
				win &= (placedLoc != null);
			}
			
			// force an update of objective status
			team.updateObjectives();
		
			// if the team won, mark the match as completed	
			if (win) matchComplete(team);
		}
	}

	// helper class for terminating world, synchronous task
	class MatchEndTask implements Runnable
	{
		public void run()
		{ destroy(); }
	}

	public static class TiebreakerComparator implements Comparator<AutoRefTeam>
	{
		public int compare(AutoRefTeam a, AutoRefTeam b)
		{
			// break ties first on the number of objectives placed, then number found
			int vmd = a.getObjectivesPlaced() - b.getObjectivesPlaced();
			return vmd == 0 ? a.getObjectivesFound() - b.getObjectivesFound() : vmd;
		}
	}

	public void matchComplete()
	{
		TiebreakerComparator cmp = new TiebreakerComparator();
		List<AutoRefTeam> sortedTeams = Lists.newArrayList(getTeams());

		// sort the teams based on their "score"
		Collections.sort(sortedTeams, cmp);

		if (0 != cmp.compare(sortedTeams.get(0), sortedTeams.get(1)))
		{ matchComplete(sortedTeams.get(0)); return; }

		if (AutoRefMatch.allowTies) { matchComplete(null); return; }
		for (Player ref : getReferees())
		{
			ref.sendMessage(ChatColor.DARK_GRAY + "This match is currently tied.");
			ref.sendMessage(ChatColor.DARK_GRAY + "Use '/autoref endmatch <team>' to declare a winner.");
		}

		// let the console know that the match cannot be ruled upon
		AutoReferee.getInstance().getLogger().info("Match tied. Deferring to referee intervention...");
		cancelClock();
	}

	public void matchComplete(AutoRefTeam t)
	{
		// announce the victory and set the match to completed
		if (t != null) this.broadcast(t.getName() + " Wins!");
		else this.broadcast("Match terminated!");
		
		// remove all mobs, animals, and items
		this.clearEntities();

		// set the time to day
		getWorld().setTime(0L);
		
		for (AutoRefPlayer apl : getPlayers())
		{
			Player pl = apl.getPlayer();
			if (pl == null) continue;
			pl.getInventory().clear();
		}
		
		// send referees the end event
		if (t != null) messageReferees("match", getWorld().getName(), "end", t.getRawName());
		else messageReferees("match", getWorld().getName(), "end");
		
		String winner = t == null ? "" : (" " + t.getRawName() + " wins!");
		addEvent(new TranscriptEvent(this, TranscriptEvent.EventType.MATCH_END,
			"Match ended." + winner, null, null, null));
		setCurrentState(MatchStatus.COMPLETED);
		
		setWinningTeam(t);
		logPlayerStats();
		cancelClock();
		
		AutoReferee plugin = AutoReferee.getInstance();
		int termDelay = plugin.getConfig().getInt(
			"delay-seconds.completed", COMPLETED_SECONDS);
		
		plugin.getServer().getScheduler().scheduleSyncDelayedTask(
			plugin, new MatchEndTask(), termDelay * 20L);
	}

	public AutoRefTeam teamNameLookup(String name)
	{
		AutoRefTeam mteam = null;
		
		// if there is no match on that world, forget it
		// is this team name a word?
		for (AutoRefTeam t : teams) if (t.matches(name))
		{ if (mteam == null) mteam = t; else return null; }
	
		// return the matched team (or null if no match)
		return mteam;
	}
	
	// teamless expected players
	Set<OfflinePlayer> expectedPlayers = Sets.newHashSet();
	
	// get all expected players
	public Set<OfflinePlayer> getExpectedPlayers()
	{
		Set<OfflinePlayer> eps = Sets.newHashSet(expectedPlayers);
		for (AutoRefTeam team : teams)
			eps.addAll(team.getExpectedPlayers());
		return eps;
	}

	public void addExpectedPlayer(OfflinePlayer opl)
	{ expectedPlayers.add(opl); }
	
	// returns the team for the expected player
	public AutoRefTeam expectedTeam(OfflinePlayer opl)
	{
		for (AutoRefTeam team : teams)
			if (team.getExpectedPlayers().contains(opl)) return team;
		return null;
	}
	
	// returns if the player is meant to join this match
	public boolean isPlayerExpected(OfflinePlayer opl)
	{ return getExpectedPlayers().contains(opl); }
	
	public void removeExpectedPlayer(OfflinePlayer opl)
	{
		for (AutoRefTeam t : teams)
			t.getExpectedPlayers().remove(opl);
		expectedPlayers.remove(opl);
	}

	public void acceptInvitation(Player pl)
	{
		// if already here, skip this
		if (this.isPlayer(pl)) return;
		
		// if this player needs to be placed on a team, go for it
		AutoRefTeam team = this.expectedTeam(pl);
		if (team != null) this.joinTeam(pl, team, false);
		
		// otherwise, get them into the world
		else if (pl.getWorld() != this.getWorld())
			pl.teleport(this.getPlayerSpawn(pl));
		
		// remove name from all lists
		this.removeExpectedPlayer(pl);
	}
	
	public boolean joinTeam(Player pl, AutoRefTeam t, boolean force)
	{
		AutoRefTeam pteam = getPlayerTeam(pl);
		if (t == pteam) return true;
		
		if (pteam != null) pteam.leave(pl, force);
		t.join(pl, force); return true;
	}
	
	public void leaveTeam(Player pl, boolean force)
	{ for (AutoRefTeam team : teams) team.leave(pl, force); }
	
	public AutoRefPlayer getPlayer(String name)
	{ return getPlayer(AutoReferee.getInstance().getServer().getPlayer(name)); }

	public AutoRefPlayer getPlayer(Player pl)
	{
		for (AutoRefTeam team : teams)
		{
			AutoRefPlayer apl = team.getPlayer(pl);
			if (apl != null) return apl;
		}
		return null;
	}
	
	public boolean isPlayer(Player pl)
	{ return getPlayer(pl) != null; }
	
	public AutoRefPlayer getNearestPlayer(Location loc)
	{
		AutoRefPlayer apl = null;
		double distance = Double.POSITIVE_INFINITY;
		
		for (AutoRefPlayer a : getPlayers())
		{
			Player pl = a.getPlayer();
			if (pl == null) continue;
			
			double d = loc.distanceSquared(pl.getLocation());
			if (d < distance) { apl = a; distance = d; }
		}
		
		return apl;
	}
	
	public AutoRefTeam getPlayerTeam(Player pl)
	{
		for (AutoRefTeam team : teams)
			if (team.getPlayer(pl) != null) return team;
		return null;
	}
	
	public String getPlayerName(Player pl)
	{
		AutoRefPlayer apl = getPlayer(pl);
		return (apl == null) ? pl.getName() : apl.getName();
	}
	
	public Location getPlayerSpawn(Player pl)
	{
		AutoRefTeam team = getPlayerTeam(pl);
		if (team != null) return team.getSpawnLocation();
		return world.getSpawnLocation();
	}
	
	public boolean isSafeZone(Location loc)
	{
		if (this.inStartRegion(loc)) return true;
		for (AutoRefTeam team : getTeams()) for (AutoRefRegion reg : team.getRegions())
			if (reg.contains(Vector3.fromLocation(loc)) && reg.isSafeZone()) return true;
		return false;
	}

	public class MatchReportSaver implements Runnable
	{
		private File localStorage = null;

		public MatchReportSaver(File f)
		{ this.localStorage = f; }

		public MatchReportSaver()
		{ this(new File(AutoReferee.getInstance().getConfig()
			.getString("local-storage.match-summary", null))); }

		public void run()
		{
			broadcast(ChatColor.RED + "Generating Match Summary...");
			String report = ReportGenerator.generate(AutoRefMatch.this);

			String localFileID = new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss").format(new Date());
			File localReport = new File(this.localStorage, localFileID + ".html");
			
			try { FileUtils.writeStringToFile(localReport, report); }
			catch (IOException e) { e.printStackTrace(); }

			String webstats = uploadReport(report);
			if (webstats != null)
			{
				AutoReferee.getInstance().getLogger().info("Match Summary - " + webstats);
				broadcast(ChatColor.RED + "Match Summary: " + ChatColor.RESET + webstats);
			}
			else broadcast(ChatColor.RED + AutoReferee.NO_WEBSTATS_MESSAGE);
		}
	}

	public void logPlayerStats()
	{
		// upload WEBSTATS (do via an async query in case uploading the stats lags the main thread)
		Plugin plugin = AutoReferee.getInstance();
		plugin.getServer().getScheduler().scheduleAsyncDelayedTask(plugin, new MatchReportSaver());
	}
	
	private String uploadReport(String report)
	{
		try
		{
			// submit our request to pastehtml, get back a link to the report
			return QueryServer.syncQuery("http://pastehtml.com/upload/create", 
				"input_type=html&result=address", "txt=" + URLEncoder.encode(report, "UTF-8"));
		}
		catch (UnsupportedEncodingException e) {  }
		return null;
	}

	// distance from the closest owned region
	public double distanceToClosestRegion(Player p)
	{
		AutoRefTeam team = getPlayerTeam(p);
		if (team != null) return team.distanceToClosestRegion(p.getLocation());
		return Double.MAX_VALUE;
	}

	// is location in start region?
	public boolean inStartRegion(Location loc)
	{ return startRegion != null && startRegion.distanceToRegion(loc) < ZoneListener.SNEAK_DISTANCE; }

	public void updateCarrying(AutoRefPlayer apl, Set<BlockData> carrying, Set<BlockData> newCarrying)
	{
		Set<BlockData> add = Sets.newHashSet(newCarrying);
		add.removeAll(carrying);

		Set<BlockData> rem = Sets.newHashSet(carrying);
		rem.removeAll(newCarrying);
		
		Player player = apl.getPlayer();
		for (BlockData bd : add) messageReferees("player", player.getName(), "obj", "+" + bd.toString());
		for (BlockData bd : rem) messageReferees("player", player.getName(), "obj", "-" + bd.toString());
	}

	public void updateHealthArmor(AutoRefPlayer apl, int currentHealth,
			int currentArmor, int newHealth, int newArmor)
	{
		Player player = apl.getPlayer();
		
		if (currentHealth != newHealth) messageReferees("player", player.getName(), 
			"hp", Integer.toString(newHealth));
		
		if (currentArmor != newArmor) messageReferees("player", player.getName(), 
			"armor", Integer.toString(newArmor));
	}
	
	public static class TranscriptEvent
	{
		public enum EventVisibility
		{ NONE, REFEREES, ALL }
		
		public enum EventType
		{
			// generic match start and end events
			MATCH_START("match-start", EventVisibility.NONE),
			MATCH_END("match-end", EventVisibility.NONE),
			
			// player messages (except kill streak) should be broadcast to players
			PLAYER_DEATH("player-death", EventVisibility.ALL),
			PLAYER_STREAK("player-killstreak", EventVisibility.NONE, ChatColor.DARK_GRAY),
			PLAYER_DOMINATE("player-dominate", EventVisibility.ALL, ChatColor.DARK_GRAY),
			PLAYER_REVENGE("player-revenge", EventVisibility.ALL, ChatColor.DARK_GRAY),
			
			// objective events should not be broadcast to players
			OBJECTIVE_FOUND("objective-found", EventVisibility.REFEREES),
			OBJECTIVE_PLACED("objective-place", EventVisibility.REFEREES);
			
			private String eventClass;
			private EventVisibility visibility;
			private ChatColor color;
			
			private EventType(String eventClass, EventVisibility visibility)
			{ this(eventClass, visibility, null); }
			
			private EventType(String eventClass, EventVisibility visibility, ChatColor color)
			{
				this.eventClass = eventClass;
				this.visibility = visibility;
				this.color = color;
			}
			
			public String getEventClass()
			{ return eventClass; }
			
			public EventVisibility getVisibility()
			{ return visibility; }
			
			public ChatColor getColor()
			{ return color; }
		}
		
		public Object icon1;
		public Object icon2;
		
		private EventType type;

		public EventType getType()
		{ return type; }
		
		private String message;
		
		public String getMessage()
		{ return message; }
		
		public Location location;
		public long timestamp;
		
		public TranscriptEvent(AutoRefMatch match, EventType type, String message, 
			Location loc, Object icon1, Object icon2)
		{
			this.type = type;
			this.message = message;
			
			// if no location is given, use the spawn location
			this.location = (loc != null) ? loc :
				match.getWorld().getSpawnLocation();
			
			// these represent left- and right-side icons for a transcript
			this.icon1 = icon1;
			this.icon2 = icon2;
			
			this.timestamp = match.getMatchTime();
		}

		public String getTimestamp()
		{
			return String.format("%02d:%02d:%02d",
				timestamp/3600L, (timestamp/60L)%60L, timestamp%60L);
		}
		
		@Override
		public String toString()
		{ return String.format("[%s] %s", this.getTimestamp(), this.getMessage()); }
	}

	public void addEvent(TranscriptEvent event)
	{
		AutoReferee plugin = AutoReferee.getInstance();
		transcript.add(event);
		
		Collection<Player> recipients = null;
		switch (event.getType().getVisibility())
		{
			case REFEREES: recipients = getReferees(false); break;
			case ALL: recipients = getWorld().getPlayers(); break;
		}
		
		ChatColor clr = event.getType().getColor();
		String message = event.getMessage();
		
		if (clr == null) message = colorMessage(message);
		else message = (clr + message + ChatColor.RESET);
		
		if (recipients != null) for (Player player : recipients)
			player.sendMessage(message);
		
		if (plugin.getConfig().getBoolean("console-log", false))
			plugin.getLogger().info(event.toString());
	}

	public List<TranscriptEvent> getTranscript()
	{ return Collections.unmodifiableList(transcript); }

	public String colorMessage(String message)
	{
		message = ChatColor.stripColor(message);
		for (AutoRefPlayer apl : getPlayers()) if (apl != null)
			message = message.replaceAll(apl.getPlayerName(), apl.getName());
		for (AutoRefTeam team : getTeams()) if (team.winConditions != null)
			for (WinCondition wc : team.winConditions) message = message.replaceAll(
				wc.getBlockData().getRawName(), wc.getBlockData().getName());
		return ChatColor.RESET + message;
	}

	public void sendMatchInfo(Player player)
	{
		player.sendMessage(ChatColor.RESET + "Map: " + ChatColor.GRAY + getMapName() + 
			" v" + getVersion() + ChatColor.ITALIC + " by " + getMapAuthors());
		
		AutoRefPlayer apl = getPlayer(player);
		String tmpflag = tmp ? "*" : "";
		
		if (apl != null) player.sendMessage("You are on team: " + apl.getTeam().getName());
		else if (isReferee(player)) player.sendMessage(ChatColor.GRAY + "You are a referee! " + tmpflag);
		else player.sendMessage("You are not on a team! Type " + ChatColor.GRAY + "/jointeam");
		
		for (AutoRefTeam team : getSortedTeams())
			player.sendMessage(String.format("%s (%d) - %s", 
				team.getName(), team.getPlayers().size(), team.getPlayerList()));
		
		long timestamp = (getWorld().getFullTime() - getStartTicks()) / 20L;
		player.sendMessage("Match status is currently " + ChatColor.GRAY + getCurrentState().name());
		player.sendMessage("Map difficulty is set to: " + ChatColor.GRAY + getWorld().getDifficulty().name());
		if (getCurrentState().inProgress())
			player.sendMessage(String.format(ChatColor.GRAY + "The current match time is: %02d:%02d:%02d", 
				timestamp/3600L, (timestamp/60L)%60L, timestamp%60L));
	}
}
