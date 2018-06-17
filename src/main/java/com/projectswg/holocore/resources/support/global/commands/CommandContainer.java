/***********************************************************************************
 * Copyright (c) 2018 /// Project SWG /// www.projectswg.com                       *
 *                                                                                 *
 * ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on          *
 * July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies. *
 * Our goal is to create an emulator which will provide a server for players to    *
 * continue playing a game similar to the one they used to play. We are basing     *
 * it on the final publish of the game prior to end-game events.                   *
 *                                                                                 *
 * This file is part of Holocore.                                                  *
 *                                                                                 *
 * --------------------------------------------------------------------------------*
 *                                                                                 *
 * Holocore is free software: you can redistribute it and/or modify                *
 * it under the terms of the GNU Affero General Public License as                  *
 * published by the Free Software Foundation, either version 3 of the              *
 * License, or (at your option) any later version.                                 *
 *                                                                                 *
 * Holocore is distributed in the hope that it will be useful,                     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of                  *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                   *
 * GNU Affero General Public License for more details.                             *
 *                                                                                 *
 * You should have received a copy of the GNU Affero General Public License        *
 * along with Holocore.  If not, see <http://www.gnu.org/licenses/>.               *
 ***********************************************************************************/
package com.projectswg.holocore.resources.support.global.commands;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CommandContainer {
	
	private final ReadWriteLock					commandLock;
	private final Map <Integer, Command>		crcToCommand;
	private final Map <String, Command>			nameToCommand;
	
	public CommandContainer() {
		this.commandLock = new ReentrantReadWriteLock(true);
		this.crcToCommand = new HashMap<>();
		this.nameToCommand = new HashMap<>();
	}
	
	public void clearCommands() {
		try {
			commandLock.writeLock().lock();
			crcToCommand.clear();
			nameToCommand.clear();
		} finally {
			commandLock.writeLock().unlock();
		}
	}
	
	public void removeCommand(Command c) {
		try {
			commandLock.writeLock().lock();
			crcToCommand.remove(c.getCrc());
			nameToCommand.remove(c.getName());
		} finally {
			commandLock.writeLock().unlock();
		}
	}
	
	public void addCommand(Command c) {
		try {
			commandLock.writeLock().lock();
			
			int crc = c.getCrc();
			String name = c.getName();
			assert !crcToCommand.containsKey(crc) : "Command is already in crc table! CRC="+crc + "  Name="+name;
			assert !nameToCommand.containsKey(name) : "Command is already in name table! CRC="+crc + "  Name="+name;
			assert name.equals(name.toLowerCase(Locale.US)) : "Invalid command name - must be all lowercase";
			
			crcToCommand.put(crc, c);
			nameToCommand.put(name, c);
		} finally {
			commandLock.writeLock().unlock();
		}
	}
	
	public boolean isCommand(int crc) {
		return getCommand(crc) != null;
	}
	
	public boolean isCommand(String name) {
		return getCommand(name) != null;
	}
	
	public Command getCommand(int crc) {
		try {
			commandLock.readLock().lock();
			return crcToCommand.get(crc);
		} finally {
			commandLock.readLock().unlock();
		}
	}
	
	public Command getCommand(String name) {
		try {
			commandLock.readLock().lock();
			return nameToCommand.get(name);
		} finally {
			commandLock.readLock().unlock();
		}
	}
	
}
