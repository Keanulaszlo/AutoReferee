package org.mctourney.AutoReferee;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.material.*;
import org.bukkit.scheduler.BukkitScheduler;

import org.mctourney.AutoReferee.AutoReferee.eMatchStatus;
import org.mctourney.AutoReferee.util.BlockData;
import org.mctourney.AutoReferee.util.CuboidRegion;
import org.mctourney.AutoReferee.util.Vector3;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Maps;

public class AutoRefMatch
{
	// world this match is taking place on
	private World world;

	public World getWorld()
	{ return world; }

	// time to set the world to at the start of the match
	private long startTime = 8000L;
	
	public long getStartTime()
	{ return startTime; }

	public void setStartTime(long startTime)
	{ this.startTime = startTime; }
	
	// status of the match
	private eMatchStatus currentState = eMatchStatus.NONE;

	public eMatchStatus getCurrentState()
	{ return currentState; }

	public void setCurrentState(eMatchStatus currentState)
	{ this.currentState = currentState; }
	
	// teams participating in the match
	private Set<AutoRefTeam> teams = null;

	public Set<AutoRefTeam> getTeams()
	{ return teams; }

	public String getTeamList()
	{
		Set<String> tlist = Sets.newHashSet();
		for (AutoRefTeam team : teams)
			tlist.add("\"" + team.getName() + "\"");
		return StringUtils.join(tlist, ", ");
	}
	
	// region defined as the "start" region (safe zone)
	private CuboidRegion startRegion = null;

	public CuboidRegion getStartRegion()
	{ return startRegion; }

	public void setStartRegion(CuboidRegion startRegion)
	{ this.startRegion = startRegion; }
	
	// name of the match
	public String matchName = "Scheduled Match";
	
	// configuration information for the world
	public File worldConfigFile;
	public FileConfiguration worldConfig;
	
	// basic variables loaded from file
	private String mapName = null;
	
	public boolean allowFriendlyFire = false;
	public boolean allowCraft = false;

	public String getMapName() 
	{ return mapName; }
	
	// task that starts the match
	public AutoRefMatch.MatchStartTask matchStarter = null;
	public Set<StartMechanism> startMechanisms = null;

	// number of seconds for each phase
	public static final int READY_SECONDS = 15;
	public static final int COMPLETED_SECONDS = 180;

	public AutoRefMatch(World world)
	{
		this.world = world;
		loadWorldConfiguration();
	}
	
	public static boolean isCompatible(World w)
	{ return new File(w.getWorldFolder(), "autoreferee.yml").exists(); }

	public static AutoReferee plugin;
	
	@SuppressWarnings("unchecked")
	private void loadWorldConfiguration()
	{
		// file stream and configuration object (located in world folder)
		worldConfigFile = new File(world.getWorldFolder(), "autoreferee.yml");
		worldConfig = YamlConfiguration.loadConfiguration(worldConfigFile);

		// load up our default values file, so that we can have a base to work with
		InputStream defConfigStream = plugin.getResource("defaults/map.yml");
		if (defConfigStream != null) worldConfig.setDefaults(
			YamlConfiguration.loadConfiguration(defConfigStream));

		// make sure any defaults get copied into the map file
		worldConfig.options().copyDefaults(true);
		worldConfig.options().header(plugin.getDescription().getFullName());
		worldConfig.options().copyHeader(false);

		teams = new HashSet<AutoRefTeam>();
		startMechanisms = new HashSet<StartMechanism>();
		
		for (Map<?, ?> map : worldConfig.getMapList("match.teams"))
			teams.add(AutoRefTeam.fromMap((Map<String, Object>) map, this));
		
		for (String sm : worldConfig.getStringList("match.start-mechanisms"))
			startMechanisms.add(StartMechanism.unserialize(world, sm));
		
		// get the start region (safe for both teams, no pvp allowed)
		if (worldConfig.isString("match.start-region"))
			startRegion = CuboidRegion.fromCoords(worldConfig.getString("match.start-region"));
		
		// get the time the match is set to start
		if (worldConfig.isString("match.start-time"))
			startTime = AutoReferee.parseTimeString(worldConfig.getString("match.start-time"));
		
		// get the extra settings cached
		mapName = worldConfig.getString("map.name", "<Untitled>");
		allowFriendlyFire = worldConfig.getBoolean("match.allow-ff", false);
		allowCraft = worldConfig.getBoolean("match.allow-craft", false);
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
		
		// save the start region
		if (startRegion != null)
			worldConfig.set("match.start-region", startRegion.toCoords());

		// save the configuration file back to the original filename
		try { worldConfig.save(worldConfigFile); }

		// log errors, report which world did not save
		catch (java.io.IOException e)
		{ plugin.getLogger().info("Could not save world config: " + world.getName()); }
	}

	public Set<AutoRefPlayer> getPlayers()
	{
		Set<AutoRefPlayer> players = Sets.newHashSet();
		for (AutoRefTeam team : teams)
			players.addAll(team.getPlayers());
		return players;
	}

	public Set<Player> getReferees()
	{
		Set<Player> refs = Sets.newHashSet();
		for (Player p : world.getPlayers())
			if (p.hasPermission("autoreferee.referee")) refs.add(p);
		refs.removeAll(getPlayers());
		return refs;
	}

	public void messageReferees(Player p, String type, String data)
	{
		byte[] msg = String.format("%s:%s:%s", p.getName(), type, data).getBytes();
		for (Player ref : getReferees())
			ref.sendPluginMessage(plugin, AutoReferee.REFEREE_PLUGIN_CHANNEL, msg);
	}

	public void broadcast(String msg)
	{ for (Player p : world.getPlayers()) p.sendMessage(msg); }

	public static String normalizeMapName(String m)
	{ return m == null ? null : m.toLowerCase().replaceAll("[^0-9a-z]+", ""); }

	public static File getMapFolder(String worldName, Long checksum) throws IOException
	{
		// assume worldName exists
		if (worldName == null) return null;
		worldName = AutoRefMatch.normalizeMapName(worldName);
		
		// if there is no map library, quit
		File mapLibrary = getMapLibrary();
		if (!mapLibrary.exists()) return null;
		
		// find the map being requested
		for (File f : mapLibrary.listFiles())
		{
			// skip non-directories
			if (!f.isDirectory()) continue;
			
			// if it doesn't have an autoreferee config file
			File cfgFile = new File(f, AutoReferee.CFG_FILENAME);
			if (!cfgFile.exists()) continue;
			
			// check the map name, if it matches, this is the one we want
			FileConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
			String cMapName = AutoRefMatch.normalizeMapName(cfg.getString("map.name"));
			if (!worldName.equals(cMapName)) continue;
			
			// compute the checksum of the directory, make sure it matches
			if (checksum != null &&	recursiveCRC32(f) != checksum) continue;
			
			// this is the map we want
			return f;
		}
		
		// no map matches
		return null;
	}

	public static long recursiveCRC32(File file) throws IOException
	{
		if (file.isDirectory())
		{
			long checksum = 0L;
			for (File f : file.listFiles())
				checksum ^= recursiveCRC32(f);
			return checksum;
		}
		else return FileUtils.checksumCRC32(file);
	}

	public static File getMapLibrary()
	{
		// maps library is a folder called `maps/`
		File m = new File("maps");
		
		// if it doesn't exist, make the directory
		if (m.exists() && !m.isDirectory()) m.delete();
		if (!m.exists()) m.mkdir();
		
		// return the maps library
		return m;
	}

	public void destroy() throws IOException
	{
		plugin.clearMatch(this);
		
		File worldFolder = world.getWorldFolder();
		plugin.getServer().unloadWorld(world, false);
		
		if (!plugin.getConfig().getBoolean("save-worlds", false))
			FileUtils.deleteDirectory(worldFolder);
	}

	public AutoRefTeam getArbitraryTeam()
	{
		// minimum size of any one team, and an array to hold valid teams
		int minsize = Integer.MAX_VALUE;
		List<AutoRefTeam> vteams = Lists.newArrayList();
		
		// get the number of players on each team: Map<TeamNumber -> NumPlayers>
		Map<AutoRefTeam,Integer> count = Maps.newHashMap();
		for (AutoRefTeam t : teams) count.put(t, 0);
		
		for (AutoRefTeam t : teams)
			if (count.containsKey(t)) count.put(t, count.get(t)+1);
		
		// determine the size of the smallest team
		for (Integer c : count.values())
			if (c < minsize) minsize = c.intValue();
	
		// make a list of all teams with this size
		for (Map.Entry<AutoRefTeam,Integer> e : count.entrySet())
			if (e.getValue().intValue() == minsize) vteams.add(e.getKey());
	
		// return a random element from this list
		return vteams.get(new Random().nextInt(vteams.size()));
	}

	static class StartMechanism
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
		{ return (o instanceof StartMechanism) && 
			hashCode() == o.hashCode(); }
		
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

	public void addStartMech(Block block, boolean state)
	{
		StartMechanism sm = new StartMechanism(block, state);
		startMechanisms.add(sm);
		plugin.getLogger().info(sm.toString() + " is a start mechanism.");
	}

	public void start()
	{
		// set up the world time one last time
		world.setTime(startTime);
		
		for (AutoRefPlayer apl : getPlayers())
		{
			// heal the players one last time
			apl.heal();
			
			// clear their inventories
			apl.getPlayer().getInventory().clear();
			
			// update the status of their objectives
			apl.updateCarrying();
		}
		
		// remove all mobs, animals, and items (again)
		for (Entity e : world.getEntitiesByClasses(Monster.class, 
			Animals.class, Item.class, ExperienceOrb.class)) e.remove();

		// loop through all the redstone mechanisms required to start
		for (StartMechanism sm : startMechanisms)
		{
			MaterialData mdata = sm.blockState.getData();
		
			// switch on the type of block
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
		
		// set the current state to playing
		currentState = eMatchStatus.PLAYING;
	}

	// helper class for starting match, synchronous task
	static class MatchStartTask implements Runnable
	{
		public int task = -1;
		private int secs = 3;
		
		private AutoRefMatch match = null;
		public MatchStartTask(AutoRefMatch m)
		{
			match = m;
		}
		
		public void run()
		{
			// if the countdown has ended...
			if (secs == 0)
			{
				// setup world to go!
				match.start();
				match.broadcast(">>> " + ChatColor.GREEN + "GO!");
				
				// cancel the task
				plugin.getServer().getScheduler().cancelTask(task);
			}
			
			// report number of seconds remaining
			else match.broadcast(">>> " + ChatColor.GREEN + 
				Integer.toString(secs--) + "...");
		}
	}

	public int getVanishLevel(Player p)
	{
		// referees have the highest vanish level
		if (p.hasPermission("autoreferee.referee")) return 200;
		
		// if you aren't on a team, you get a vanish level
		if (getPlayerTeam(p) == null) return 100;
		
		// streamers are ONLY able to see streamers and players
		if (p.hasPermission("autoreferee.streamer")) return 1;
		
		// players have the lowest level vanish
		return 0;
	}

	// prepare this world to start
	public void prepareMatch()
	{
		BukkitScheduler scheduler = plugin.getServer().getScheduler();
		
		// set the current time to the start time
		world.setTime(this.startTime);
		
		// remove all mobs, animals, and items
		for (Entity e : world.getEntitiesByClasses(Monster.class, 
			Animals.class, Item.class, ExperienceOrb.class)) e.remove();
		
		// turn off weather forever (or for a long time)
		world.setStorm(false);
		world.setWeatherDuration(Integer.MAX_VALUE);
		
		// prepare all players for the match
		for (AutoRefPlayer apl : getPlayers()) apl.heal();
		
		// vanish players appropriately
		for ( Player view : world.getPlayers() ) // <--- viewer
		for ( Player subj : world.getPlayers() ) // <--- subject
		{
			if (getVanishLevel(view) >= getVanishLevel(subj))
				view.showPlayer(subj); else view.hidePlayer(subj);
		}
		
		int readyDelay = plugin.getConfig().getInt(
			"delay-seconds.ready", AutoRefMatch.READY_SECONDS);
		
		// announce the match starting in X seconds
		this.broadcast("Match will begin in "
			+ Integer.toString(readyDelay) + " seconds.");
		
		// cancel any previous match-start task
		if (this.matchStarter != null && this.matchStarter.task != -1)
			scheduler.cancelTask(this.matchStarter.task);
		
		// schedule the task to announce and prepare the match
		this.matchStarter = new MatchStartTask(this);
		this.matchStarter.task = scheduler.scheduleSyncRepeatingTask(
				plugin, this.matchStarter, readyDelay * 20L, 20L);
	}

	public void checkTeamsReady() 
	{
		// if there are no players on the server
		if (getPlayers().size() == 0)
		{
			// set all the teams to not ready and status as waiting
			for ( AutoRefTeam t : teams ) t.setReady(false);
			setCurrentState(eMatchStatus.WAITING); return;
		}
		
		// this function is only useful if we are waiting
		if (currentState != eMatchStatus.WAITING) return;
		
		// if we aren't in online mode, assume we are always ready
		if (!plugin.isAutoMode()) { setCurrentState(eMatchStatus.READY); return; }
		
		// check if all the players are here
		boolean ready = true;
		for ( OfflinePlayer opl : getExpectedPlayers() )
			ready &= opl.isOnline() && getPlayer(opl.getPlayer()) != null &&
				getPlayer(opl.getPlayer()).isReady();
		
		// set status based on whether the players are online
		currentState = ready ? eMatchStatus.READY : eMatchStatus.WAITING;
	}

	public static void setupWorld(World w)
	{
		// if this map isn't compatible with AutoReferee, quit...
		if (plugin.getMatch(w) != null || !isCompatible(w)) return;
		plugin.addMatch(new AutoRefMatch(w));
	}

	// helper class for terminating world, synchronous task
	class MatchEndTask implements Runnable
	{
		public void run()
		{
			// first, handle all the players
			for (Player p : world.getPlayers()) plugin.playerDone(p);
			
			// then, cleanup the match object (swallow exceptions)
			try { destroy(); } catch (Exception e) {  };
		}
	}
	
	public void checkWinConditions(Location aloc)
	{
		// this code is only called in BlockPlaceEvent and BlockBreakEvent when
		// we have confirmed that the state is PLAYING, so we know we are definitely
		// in a match if this function is being called
		
		if (currentState == eMatchStatus.PLAYING) for (AutoRefTeam t : this.teams)
		{
			// if there are no win conditions set, skip this team
			if (t.winConditions.size() == 0) continue;
			
			// check all win condition blocks (AND together)
			boolean win = true;
			for (Map.Entry<Location, BlockData> pair : t.winConditions.entrySet())
			{
				BlockData bd = pair.getValue();
				win &= pair.getKey().equals(aloc) ? bd.getMaterial() == Material.AIR : 
					bd.matches(world.getBlockAt(pair.getKey()));
			}
			
			if (win)
			{
				// announce the victory and set the match to completed
				this.broadcast(t.getName() + " Wins!");
				for (AutoRefPlayer apl : getPlayers())
				{
					apl.getPlayer().teleport(world.getSpawnLocation());
					apl.getPlayer().setGameMode(GameMode.CREATIVE);
				}
				
				this.currentState = AutoReferee.eMatchStatus.COMPLETED;
				logPlayerStats(null);
				
				int termDelay = plugin.getConfig().getInt(
					"delay-seconds.completed", COMPLETED_SECONDS);
				
				plugin.getServer().getScheduler().scheduleSyncDelayedTask(
					plugin, new MatchEndTask(), termDelay * 20L);
			}
		}
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
	
	// get all expected players
	public Set<OfflinePlayer> getExpectedPlayers()
	{
		Set<OfflinePlayer> eps = Sets.newHashSet();
		for (AutoRefTeam team : teams)
			eps.addAll(team.getExpectedPlayers());
		return eps;
	}
	
	// returns the team for the expected player
	public AutoRefTeam expectedTeam(OfflinePlayer opl)
	{
		for (AutoRefTeam team : teams)
			if (team.getExpectedPlayers().contains(opl)) return team;
		return null;
	}
	
	// returns if the player is meant to join this match
	public boolean isPlayerExpected(OfflinePlayer opl)
	{ return expectedTeam(opl) != null; }
	
	public void leaveTeam(Player pl)
	{ for (AutoRefTeam team : teams) team.leave(pl); }
	
	public AutoRefPlayer getPlayer(Player pl)
	{
		for (AutoRefTeam team : teams)
		{
			AutoRefPlayer apl = team.getPlayer(pl);
			if (apl != null) return apl;
		}
		return null;
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

	void logPlayerStats(String h)
	{
		String hdl = h != null ? h : 
			new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
		
		try
		{
			File sfile = new File(plugin.getLogDirectory(), hdl + ".log");
			PrintWriter fw = new PrintWriter(sfile);
			
			for (AutoRefPlayer apl : getPlayers()) apl.writeStats(fw);
			fw.close();
		}
		catch (IOException e)
		{ plugin.getLogger().severe("Could not write player stat logfile."); }
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
	{ return startRegion.distanceToRegion(loc) < ZoneListener.SNEAK_DISTANCE; }

	public Set<AutoRefTeam> locationOwnership(Location loc)
	{
		// teams who own this location
		Set<AutoRefTeam> owners = new HashSet<AutoRefTeam>();
	
		// check all safe regions for that team
		for (AutoRefTeam team : teams)
			for (CuboidRegion reg : team.getRegions())
		{
			// if the location is inside the region, add it
			if (reg.distanceToRegion(loc) < ZoneListener.SNEAK_DISTANCE) 
				owners.add(team);
		}
		
		return owners;
	}

	public void updateCarrying(AutoRefPlayer apl, Set<BlockData> carrying, Set<BlockData> newCarrying)
	{
		Set<BlockData> add = Sets.newHashSet(newCarrying);
		add.removeAll(carrying);

		Set<BlockData> rem = Sets.newHashSet(carrying);
		rem.removeAll(newCarrying);
		
		Player player = apl.getPlayer();
		for (BlockData bd : add) messageReferees(player, "obj", "+" + bd.toString());
		for (BlockData bd : rem) messageReferees(player, "obj", "-" + bd.toString());
	}

	public void updateHealthArmor(AutoRefPlayer apl, int currentHealth,
			int currentArmor, int newHealth, int newArmor)
	{
		Player player = apl.getPlayer();
		
		if (currentHealth != newHealth)
			messageReferees(player, "hp", Integer.toString(newHealth));
		
		if (currentArmor != newArmor)
			messageReferees(player, "armor", Integer.toString(newArmor));
	}
}
