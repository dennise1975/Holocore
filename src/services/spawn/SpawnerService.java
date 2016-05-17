/***********************************************************************************
* Copyright (c) 2015 /// Project SWG /// www.projectswg.com                        *
*                                                                                  *
* ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on           *
* July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies.  *
* Our goal is to create an emulator which will provide a server for players to     *
* continue playing a game similar to the one they used to play. We are basing      *
* it on the final publish of the game prior to end-game events.                    *
*                                                                                  *
* This file is part of Holocore.                                                   *
*                                                                                  *
* -------------------------------------------------------------------------------- *
*                                                                                  *
* Holocore is free software: you can redistribute it and/or modify                 *
* it under the terms of the GNU Affero General Public License as                   *
* published by the Free Software Foundation, either version 3 of the               *
* License, or (at your option) any later version.                                  *
*                                                                                  *
* Holocore is distributed in the hope that it will be useful,                      *
* but WITHOUT ANY WARRANTY; without even the implied warranty of                   *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                    *
* GNU Affero General Public License for more details.                              *
*                                                                                  *
* You should have received a copy of the GNU Affero General Public License         *
* along with Holocore.  If not, see <http://www.gnu.org/licenses/>.                *
*                                                                                  *
***********************************************************************************/
package services.spawn;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

import intents.object.ObjectCreatedIntent;
import intents.server.ConfigChangedIntent;
import resources.Location;
import resources.PvpFlag;
import resources.Terrain;
import resources.config.ConfigFile;
import resources.control.Intent;
import resources.control.Service;
import resources.objects.building.BuildingObject;
import resources.objects.SWGObject;
import resources.objects.creature.CreatureDifficulty;
import resources.objects.creature.CreatureObject;
import resources.objects.custom.AIBehavior;
import resources.objects.custom.DefaultAIObject;
import resources.objects.tangible.OptionFlag;
import resources.server_info.Log;
import resources.server_info.RelationalDatabase;
import resources.server_info.RelationalServerFactory;
import resources.spawn.SpawnerType;
import resources.spawn.Spawner;
import services.objects.ObjectCreator;
import services.objects.ObjectManager;

public final class SpawnerService extends Service {
	
	private static final String GET_ALL_SPAWNERS_SQL = "SELECT static.x, static.y, static.z, static.heading, " // static columns
			+ "static.spawner_type, static.cell_id, static.active, static.mood, static.behaviour, static.float_radius, " // more static columns
			+ "buildings.object_id AS building_id, buildings.terrain_name AS building_terrain, " // building columns
			+ "creatures.iff_template AS iff, creatures.creature_name, creatures.combat_level, creatures.difficulty, creatures.attackable, " // creature columns
			+ "npc_stats.HP, npc_stats.Action, npc_stats.Boss_HP, npc_stats.Boss_Action, npc_stats.Elite_HP, npc_stats.Elite_Action "	// npc_stats columns
			+ "FROM static, buildings, creatures, npc_stats "
			+ "WHERE buildings.building_id = static.building_id AND static.creature_id = creatures.creature_id AND creatures.combat_level = npc_stats.Level";
	private static final String IDLE_MOOD = "idle";
	
	private final ObjectManager objectManager;
	private final Collection<Spawner> spawners;
	
	public SpawnerService(ObjectManager objectManager) {
		this.objectManager = objectManager;
		spawners = new ArrayList<>();
		
		registerForIntent(ConfigChangedIntent.TYPE);
	}
	
	@Override
	public boolean initialize() {
		if (getConfig(ConfigFile.FEATURES).getBoolean("NPCS-ENABLED", true))
			loadSpawners(getConfig(ConfigFile.FEATURES).getBoolean("SPAWN-EGGS-ENABLED", false));
		
		return super.initialize();
	}
	
	@Override
	public void onIntentReceived(Intent i) {
		if (!(i instanceof ConfigChangedIntent))
			return;
		ConfigChangedIntent cgi = (ConfigChangedIntent) i;
		String newValue, oldValue;
		
		if (cgi.getChangedConfig().equals(ConfigFile.FEATURES)) {
			if (cgi.getKey().equals("NPCS-ENABLED")) {
				newValue = cgi.getNewValue();
				oldValue = cgi.getOldValue();
				
				if (!newValue.equals(oldValue)) {
					if (Boolean.valueOf(newValue) && spawners.isEmpty()) { // If nothing's been spawned, create it.
						loadSpawners(getConfig(ConfigFile.FEATURES).getBoolean("SPAWN-EGGS-ENABLED", false));
					} else { // If anything's been spawned, delete it.
						removeSpawners();
					}
				}
			}
		}
	}
	
	private void loadSpawners(boolean spawnEggs) {
		long start = System.nanoTime();
		int count = 0;
		Log.i(this, "Loading NPCs...");
		try (RelationalDatabase spawnerDatabase = RelationalServerFactory.getServerData("spawn/static.db", "static", "building/buildings", "creatures/creatures", "creatures/npc_stats")) {
			try (ResultSet set = spawnerDatabase.executeQuery(GET_ALL_SPAWNERS_SQL)) {
				Location loc = new Location();
				while (set.next()) {
					if (set.getBoolean("active")) {
						loadSpawner(set, loc, spawnEggs);
						count++;
					}
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		double time = (System.nanoTime()-start)/1E6;
		Log.i(this, "Finished loading %d NPCs. Time: %fms", count, time);
	}
	
	private void loadSpawner(ResultSet set, Location loc, boolean spawnEggs) throws SQLException {
		loc.setTerrain(Terrain.valueOf(set.getString("building_terrain")));
		loc.setPosition(set.getFloat("x"), set.getFloat("y"), set.getFloat("z"));
		loc.setHeading(set.getFloat("heading"));
		int cellId = set.getInt("cell_id");
		
		SWGObject parent = null;
		if (cellId > 0) {
			parent = objectManager.getObjectById(set.getLong("building_id"));
			if (parent instanceof BuildingObject)
				parent = ((BuildingObject) parent).getCellByNumber(cellId);
		}
		
		if (spawnEggs) {
			SpawnerType spawnerType = SpawnerType.valueOf(set.getString("spawner_type"));
			SWGObject egg = objectManager.createObject(parent, spawnerType.getObjectTemplate(), loc, false);
			spawners.add(new Spawner(egg));
		}
		String difficultyChar = set.getString("difficulty");
		String creatureName = set.getString("creature_name");
		CreatureDifficulty difficulty;
		
		switch(difficultyChar) {
			default: Log.w(this, "An unknown creature difficulty of %s was set for %s. Using default NORMAL", difficultyChar, creatureName);
			case "N": difficulty = CreatureDifficulty.NORMAL; break;
			case "E": difficulty = CreatureDifficulty.ELITE; break;
			case "B": difficulty = CreatureDifficulty.BOSS; break;
		}
		
		createNPC(parent, loc, creatureName, difficulty, set);
	}
	
	private void createNPC(SWGObject parent, Location loc, String name, CreatureDifficulty difficulty, ResultSet set) throws SQLException {
		DefaultAIObject object = ObjectCreator.createObjectFromTemplate(createTemplate(getRandomIff(set.getString("iff"))), DefaultAIObject.class);
		object.setLocation(loc);
		if (parent != null)
			parent.addObject(object);
		object.setName(getCreatureName(name));
		object.setLevel((short) set.getInt("combat_level"));
		object.setDifficulty(difficulty);
		setHAM(object, difficulty, set);
		setFlags(object, set);
		
		object.setBehavior(AIBehavior.valueOf(set.getString("behaviour")));
		if (object.getBehavior() == AIBehavior.FLOAT)
			object.setFloatRadius((Integer) set.getInt("float_radius"));
		
		String moodAnimation = set.getString("mood");
		if (!moodAnimation.equals(IDLE_MOOD)) {
			object.setMoodAnimation(moodAnimation);
		}
		new ObjectCreatedIntent(object).broadcast();
	}
	
	private void setHAM(CreatureObject creature, CreatureDifficulty difficulty, ResultSet set) throws SQLException {
		int health = 0, action = 0;
		switch (difficulty) {
			case BOSS:
				health = set.getInt("Boss_HP");
				action = set.getInt("Boss_Action");
				break;
			case ELITE:
				health = set.getInt("Elite_HP");
				action = set.getInt("Elite_Action");
				break;
			case NORMAL:
				health = set.getInt("HP");
				action = set.getInt("Action");
				break;
		}
		creature.setMaxHealth(health);
		creature.setHealth(health);
		creature.setMaxAction(action);
		creature.setAction(action);
	}
	
	private void setFlags(CreatureObject creature, ResultSet set) throws SQLException {
		switch (set.getString("attackable")) {
			case "AGGRESSIVE":
				creature.setPvpFlags(PvpFlag.AGGRESSIVE);
				creature.addOptionFlags(OptionFlag.AGGRESSIVE);
			case "ATTACKABLE":
				creature.setPvpFlags(PvpFlag.ATTACKABLE);
				creature.addOptionFlags(OptionFlag.HAM_BAR);
				break;
			case "INVULNERABLE":
				creature.addOptionFlags(OptionFlag.INVULNERABLE);
				break;
			default:
				Log.w(this, "An unknown attackable type of %s was specified for %s", set.getString("attackable"), creature.getName());
				break;
		}
	}
	
	private String getCreatureName(String name) {
		return name.replace("(", "\n(");
	}
	
	private String getRandomIff(String semicolonSeparated) {
		String [] possible = semicolonSeparated.split(";");
		return possible[(int) (Math.random()*possible.length)];
	}
	
	private String createTemplate(String template) {
		if (template.indexOf('/') != -1) {
			int ind = template.lastIndexOf('/');
			return "object/mobile/" + template.substring(0, ind) + "/shared_" + template.substring(ind+1);
		} else
			return "object/mobile/shared_" + template;
	}
	
	private void removeSpawners() {
		for(Spawner spawner : spawners)
			objectManager.destroyObject(spawner.getSpawnerObject());
		
		spawners.clear();
	}
}