//LinearMemory
//shamelessly copied from Java PC's Linear Memory

package simulator;

import java.util.ArrayList;
import java.util.Scanner;

public class LinearMemory implements MemoryDevice
{
	Computer computer;
	
	//page block size is 4096
	private static final int BLOCK_SIZE=4096;
	private static final int BLOCK_OFFSET_MASK=0xfff;
	private static final int PAGE_NUMBER_SHIFT=12;
	private static final int PAGES=1<<(32-PAGE_NUMBER_SHIFT);

	public boolean isSupervisor,globalPagesEnabled,pagingDisabled,writeProtectUserPages,pageSizeExtensions,pageCacheEnabled;
	public int directoryBaseAddress=0;
	public int lastPageFaultAddress=0;
	
	//four TLB tables
	private PageTableEntry[] readSupervisorPageTable, readUserPageTable, writeSupervisorPageTable, writeUserPageTable;
	//size of each page: 4k or 4M
	private boolean[] pageSize;
	
	//page fault error codes
	private static final int PF_RS_NOTPRESENT=0;
	private static final int PF_RU_NOTPRESENT=4;
	private static final int PF_WS_NOTPRESENT=2;
	private static final int PF_WU_NOTPRESENT=6;
	private static final int PF_RU_PROTECTION=5;
	private static final int PF_WU_PROTECTION=7;
	private static final int PF_WS_PROTECTION=3;
	
	//the non global pages are removed from the TLB on a task switch
	//this keeps track of their indices
	private ArrayList<Integer> nonGlobalPageList;

	public LinearMemory(Computer computer)
	{
		this.computer=computer;
		pagingDisabled=true;
		nonGlobalPageList=new ArrayList<Integer>();
		flush();
	}
	
	public void loadState(String state)
	{
		Scanner s=new Scanner(state);
		if (!s.next().equals("LinearMemory"))
		{
			System.out.println("Error in load state: LinearMemory expected");
			return;
		}
		isSupervisor=s.nextInt()==1;
		globalPagesEnabled=s.nextInt()==1;
		pagingDisabled=s.nextInt()==1;
		writeProtectUserPages=s.nextInt()==1;
		pageSizeExtensions=s.nextInt()==1;
		pageCacheEnabled=s.nextInt()==1;
		directoryBaseAddress=s.nextInt();
		lastPageFaultAddress=s.nextInt();
		if(s.nextInt()!=0)
		{
			readSupervisorPageTable=new PageTableEntry[PAGES];
			for (int i=0; i<PAGES; i++)
			{
				if (s.nextInt()!=0)
					readSupervisorPageTable[i]=new PageTableEntry(s.nextInt());
			}
		}
		if(s.nextInt()!=0)
		{
			writeSupervisorPageTable=new PageTableEntry[PAGES];
			for (int i=0; i<PAGES; i++)
			{
				if (s.nextInt()!=0)
					writeSupervisorPageTable[i]=new PageTableEntry(s.nextInt());
			}
		}
		if(s.nextInt()!=0)
		{
			readUserPageTable=new PageTableEntry[PAGES];
			for (int i=0; i<PAGES; i++)
			{
				if (s.nextInt()!=0)
					readUserPageTable[i]=new PageTableEntry(s.nextInt());
			}
		}
		if(s.nextInt()!=0)
		{
			writeUserPageTable=new PageTableEntry[PAGES];
			for (int i=0; i<PAGES; i++)
			{
				if (s.nextInt()!=0)
					writeUserPageTable[i]=new PageTableEntry(s.nextInt());
			}
		}
		if (s.nextInt()!=0)
		{
			pageSize=new boolean[PAGES];
			for (int i=0; i<PAGES; i++)
				pageSize[i]=s.nextInt()==1;
		}
		nonGlobalPageList=new ArrayList<Integer>();
		int ngpl=s.nextInt();
		for (int i=0; i<ngpl; i++)
			nonGlobalPageList.add(new Integer(s.nextInt()));
	}
	
	public String saveState()
	{
		StringBuffer state=new StringBuffer();
		state.append("LinearMemory ");
		state.append((isSupervisor?1:0)+" ");
		state.append((globalPagesEnabled?1:0)+" ");
		state.append((pagingDisabled?1:0)+" ");
		state.append((writeProtectUserPages?1:0)+" ");
		state.append((pageSizeExtensions?1:0)+" ");
		state.append((pageCacheEnabled?1:0)+" ");
		state.append(directoryBaseAddress+" ");
		state.append(lastPageFaultAddress+" ");
		if (readSupervisorPageTable==null)
			state.append(0+" ");
		else
		{
			state.append(1+" ");
			for (int i=0; i<readSupervisorPageTable.length; i++)
			{
				if (readSupervisorPageTable[i]==null)
					state.append("0 ");
				else
					state.append("1 "+readSupervisorPageTable[i].physicalBaseAddress+" ");
			}
		}
		if (writeSupervisorPageTable==null)
			state.append(0+" ");
		else
		{
			state.append(1+" ");
			for (int i=0; i<writeSupervisorPageTable.length; i++)
			{
				if (writeSupervisorPageTable[i]==null)
					state.append("0 ");
				else
					state.append("1 "+writeSupervisorPageTable[i].physicalBaseAddress+" ");
			}
		}
		if (readUserPageTable==null)
			state.append(0+" ");
		else
		{
			state.append(1+" ");
			for (int i=0; i<readUserPageTable.length; i++)
			{
				if (readUserPageTable[i]==null)
					state.append("0 ");
				else
					state.append("1 "+readUserPageTable[i].physicalBaseAddress+" ");
			}
		}
		if (writeUserPageTable==null)
			state.append(0+" ");
		else
		{
			state.append(1+" ");
			for (int i=0; i<writeUserPageTable.length; i++)
			{
				if (writeUserPageTable[i]==null)
					state.append("0 ");
				else
					state.append("1 "+writeUserPageTable[i].physicalBaseAddress+" ");
			}
		}
		if (pageSize==null)
			state.append(0+" ");
		else
		{
			state.append(1+" ");
			for (int i=0; i<pageSize.length; i++)
				state.append((pageSize[i]?1:0)+" ");
		}
		state.append(nonGlobalPageList.size()+" ");
		for(int i=0; i<nonGlobalPageList.size(); i++)
			state.append(nonGlobalPageList.get(i)+" ");
		return state.toString();
	}
	
	public byte getByte(int address) 
	{
		return computer.physicalMemory.getByte(getPhysicalPageRead(address));
//		return computer.physicalMemory.getByte(getPhysicalPageRead(address)|(address & BLOCK_OFFSET_MASK));
	}
	public void setByte(int address, byte value) 
	{
		computer.physicalMemory.setByte(getPhysicalPageWrite(address), value);
//		computer.physicalMemory.setByte(getPhysicalPageWrite(address)|(address & BLOCK_OFFSET_MASK),value);
	}
	public short getWord(int address)
	{
		return (short)((getByte(address)&0xff)|((getByte(address+1)<<8)&0xff00));
	}
	public int getDoubleWord(int address)
	{
		return (getWord(address)&0xffff)|((getWord(address+2)<<16)&0xffff0000);
        }
	public long getQuadWord(int address)
	{
		return (getDoubleWord(address)&0xffffffffl)|((((long)getDoubleWord(address+4))<<32)&0xffffffff00000000l);
	}
	public void setWord(int address, short value)
	{
		setByte(address,(byte)value);
		setByte(address+1,(byte)(value>>8));
	}
	public void setDoubleWord(int address, int value)
	{
		setByte(address,(byte)value);
		setByte(address+1,(byte)(value>>8));
		setByte(address+2,(byte)(value>>16));
		setByte(address+3,(byte)(value>>24));
	}
	public void setQuadWord(int address, long value)
	{
		setByte(address,(byte)value);
		setByte(address+1,(byte)(value>>8));
		setByte(address+2,(byte)(value>>16));
		setByte(address+3,(byte)(value>>24));
		setByte(address+4,(byte)(value>>32));
		setByte(address+5,(byte)(value>>40));
		setByte(address+6,(byte)(value>>48));
		setByte(address+7,(byte)(value>>56));
	}
	
	static int quantity=0;
	private class PageTableEntry
	{
		int physicalBaseAddress;
		public PageTableEntry(int address)
		{
			physicalBaseAddress=address;
//			System.out.println("new page entry: "+address+" "+(quantity++));
		}
	}
	
	//used for GUI purposes, not for emulation
	//does a page table lookup, returns physical address
	public int virtualAddressLookup(int virtualAddress)
	{
		if (pagingDisabled)
			return virtualAddress;
		int offset=virtualAddress&BLOCK_OFFSET_MASK;
		int virtualPageIndex=virtualAddress>>PAGE_NUMBER_SHIFT;
		//get the information location in the page table directory
		//determined by the upper 10 bits. each directory entry is 4 bytes.
		int directoryAddress=directoryBaseAddress | (0xfff & ((virtualPageIndex>>>10)*4));
		int directoryInformation=computer.physicalMemory.getDoubleWord(directoryAddress);
		//is the directory there?
		if ((directoryInformation&1)==0)
			return -1;
		boolean directoryIs4MPage=((0x80&directoryInformation)!=0) && pageSizeExtensions;
		if (!directoryIs4MPage)
		{
			//extract the page table: determined by the middle 10 bits
			int pageTableEntryAddress=(directoryInformation&0xfffff000) | ((virtualPageIndex*4)&0xfff);
			int pageTableEntry=computer.physicalMemory.getDoubleWord(pageTableEntryAddress);
			//is it there?
			if ((pageTableEntry&1)==0)
				return -1;
			int physicalBaseAddress=pageTableEntry&0xfffff000;
			return physicalBaseAddress|offset;
		}
		else
		{
			//extract the physical address straight from the directory
			int physicalBaseAddress=(0xffc00000&directoryInformation);
			return physicalBaseAddress|(virtualAddress&0x3fffff);
		}
	}

	//given a virtual address, get the physical address
	private int getPhysicalPageRead(int virtualAddress)
	{		
		//if paging is disabled, the virtual address becomes the physical address
		if (pagingDisabled)
			return virtualAddress;
		int virtualPageIndex=virtualAddress>>>PAGE_NUMBER_SHIFT;
		int offset=virtualAddress&BLOCK_OFFSET_MASK;
		//two direct mapped page tables in the TLB: supervisor and user
		if (isSupervisor)
		{
			//if the page table doesn't exist, initialize it
			//initially, all the entries will be NULL
			if (readSupervisorPageTable==null)
				readSupervisorPageTable=new PageTableEntry[PAGES];
			//get the entry's base address
			try
			{
				return (readSupervisorPageTable[virtualPageIndex].physicalBaseAddress)|offset;
			}
			catch(NullPointerException e){}
			//if the entry was NULL, construct it and repeat
			return validateTLBEntryRead(virtualAddress);
		}
		else
		{
			if (readUserPageTable==null)
				readUserPageTable=new PageTableEntry[PAGES];
			try
			{
				return readUserPageTable[virtualPageIndex].physicalBaseAddress|offset;
			}
			catch(NullPointerException e){}
			return validateTLBEntryRead(virtualAddress);
		}
	}
	//same as read, but with the write page tables
	private int getPhysicalPageWrite(int virtualAddress)
	{
		if (pagingDisabled)
			return virtualAddress;
		int virtualPageIndex=virtualAddress>>>PAGE_NUMBER_SHIFT;
		int offset=virtualAddress&BLOCK_OFFSET_MASK;
		if (isSupervisor)
		{
			if (writeSupervisorPageTable==null)
				writeSupervisorPageTable=new PageTableEntry[PAGES];
			try
			{
				return writeSupervisorPageTable[virtualPageIndex].physicalBaseAddress|offset;
			}
			catch(NullPointerException e){}
			return validateTLBEntryWrite(virtualAddress);
		}
		else
		{
			if (writeUserPageTable==null)
				writeUserPageTable=new PageTableEntry[PAGES];
			try
			{
				return writeUserPageTable[virtualPageIndex].physicalBaseAddress|offset;
			}
			catch(NullPointerException e){}
			return validateTLBEntryWrite(virtualAddress);
		}
	}

	private int validateTLBEntryRead(int virtualAddress)
	{
//		System.out.println("validate TLB entry read "+ virtualAddress);
		int virtualPageIndex=virtualAddress>>>PAGE_NUMBER_SHIFT;
		int offset=virtualAddress&BLOCK_OFFSET_MASK;
		lastPageFaultAddress=virtualAddress;
		
		//virtual address is divided up as follows:
		//[10 bits: directory entry][10 bits: page table entry][12 bits: block offset]
		
		//get the information location in the page table directory
		//determined by the upper 10 bits. each directory entry is 4 bytes.
		int directoryAddress=directoryBaseAddress | (0xfff & ((virtualPageIndex>>>10)*4));
		int directoryInformation=computer.physicalMemory.getDoubleWord(directoryAddress);
		//is the directory there?
		if ((directoryInformation&1)==0) 
		{
			panic("directory isn't there");
			if (isSupervisor)
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_RS_NOTPRESENT);
			else
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_RU_NOTPRESENT);
		}
		//extract information about the page table directory
		//is it user or supervisor?
		boolean directoryIsUser = (4&directoryInformation)!=0;
		//is it 4M or 4k?
		boolean directoryIs4MPage=((0x80&directoryInformation)!=0) && pageSizeExtensions;
		
		if (!directoryIs4MPage)
		{
			//extract the page table: determined by the middle 10 bits
			int pageTableEntryAddress=(directoryInformation&0xfffff000) | ((virtualPageIndex*4)&0xfff);
			int pageTableEntry=computer.physicalMemory.getDoubleWord(pageTableEntryAddress);
			//is it there?
			if ((pageTableEntry&1)==0) 
			{
				panic("page table entry isn't there");
				if (isSupervisor)
					throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_RS_NOTPRESENT);
				else
					throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_RU_NOTPRESENT);
			}
			boolean tableIsUser = (4&pageTableEntry)!=0;
			//is a user accessing a supervisor page?
			if ((!tableIsUser || !directoryIsUser) && !isSupervisor)
			{
				panic("user trying to access a supervisor page");
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_RU_PROTECTION);
			}
			//set bit 17 to 1
			if ((pageTableEntry&0x20)==0)
			{
				pageTableEntry|=0x20;
				computer.physicalMemory.setDoubleWord(pageTableEntryAddress, pageTableEntry);
			}
			int physicalBaseAddress=pageTableEntry&0xfffff000;
			//if page caching is disabled, don't make a TLB entry.  just return the physical base address.
			if (!pageCacheEnabled)
				return physicalBaseAddress|offset;

			//save the entry in the TLB
			if (isSupervisor)
				readSupervisorPageTable[virtualPageIndex]=new PageTableEntry(physicalBaseAddress);
			else
				readUserPageTable[virtualPageIndex]=new PageTableEntry(physicalBaseAddress);
			return physicalBaseAddress|offset;
		}
		else
		{
			if (!directoryIsUser && !isSupervisor)
			{
				panic("User is trying to access a supervisor directory entry");
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_RU_PROTECTION);
			}
			if ((directoryInformation&0x20)==0)
			{
				directoryInformation|=0x20;
				computer.physicalMemory.setDoubleWord(directoryAddress, directoryInformation);
			}
			int physicalBaseAddress=(0xffc00000&directoryInformation);
			//if no page caching, don't make a TLB entry
			if (!pageCacheEnabled)
				return physicalBaseAddress|(virtualAddress&0x3ffffff);
			int pageSizeIndex=(0xffc00000&virtualAddress)>>>12;
			//create 1024 (1Ms worth) of TLB entries
			for (int i=0; i<1024; i++)
			{
				pageSize[pageSizeIndex]=true;
				if (isSupervisor)
				{
					if (readSupervisorPageTable==null)
						readSupervisorPageTable=new PageTableEntry[PAGES];
					readSupervisorPageTable[pageSizeIndex]=new PageTableEntry(physicalBaseAddress);
					pageSizeIndex++;
					physicalBaseAddress+=BLOCK_SIZE;
				}
				else
				{
					if (readUserPageTable==null)
						readUserPageTable=new PageTableEntry[PAGES];
					readUserPageTable[pageSizeIndex]=new PageTableEntry(physicalBaseAddress);
					pageSizeIndex++;
					physicalBaseAddress+=BLOCK_SIZE;
				}
			}
			if (isSupervisor)
				return readSupervisorPageTable[virtualPageIndex].physicalBaseAddress|offset;
			else
				return readUserPageTable[virtualPageIndex].physicalBaseAddress|offset;
		}		
	}
	
	private int validateTLBEntryWrite(int virtualAddress)
	{
//		System.out.println("validate TLB entry write "+ virtualAddress);
		int virtualPageIndex=virtualAddress>>>PAGE_NUMBER_SHIFT;
		int offset=virtualAddress&BLOCK_OFFSET_MASK;
		lastPageFaultAddress=virtualAddress;
		
		//virtual address is divided up as follows:
		//[10 bits: directory entry][10 bits: page table entry][12 bits: block offset]
		
		//get the information location in the page table directory
		//determined by the upper 10 bits. each directory entry is 4 bytes.
		int directoryAddress=directoryBaseAddress | (0xfff & ((virtualPageIndex>>>10)*4));
		int directoryInformation=computer.physicalMemory.getDoubleWord(directoryAddress);
		//is the directory there?
		if ((directoryInformation&1)==0)
		{
			panic("directory isn't there");
			if (isSupervisor)
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WS_NOTPRESENT);
			else
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_NOTPRESENT);
		}

		//extract information about the page table directory
		//user or supervisor?
		boolean directoryIsUser = (4&directoryInformation)!=0;
		//is it 4M or 4k?
		boolean directoryIs4MPage=((0x80&directoryInformation)!=0) && pageSizeExtensions;
		//is it writeable?
		boolean directoryReadWrite=(0x2&directoryInformation)!=0;
		
		if (!directoryIs4MPage)
		{
			//extract the page table: determined by the middle 10 bits
			int pageTableEntryAddress=(directoryInformation&0xfffff000) | ((virtualPageIndex*4)&0xfff);
			int pageTableEntry=computer.physicalMemory.getDoubleWord(pageTableEntryAddress);
			//is it there?
			if ((pageTableEntry&1)==0)
			{
				panic("page table entry isn't there");
				if (isSupervisor)
					throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WS_NOTPRESENT);
				else
					throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_NOTPRESENT);
			}
			boolean tableIsUser = (4&pageTableEntry)!=0;
			//is a user accessing a supervisor page?
			if ((!tableIsUser || !directoryIsUser) && !isSupervisor)
			{
				panic("user trying to access a supervisor page");
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
			}
			//is it writeable?
			boolean tableIsReadWrite=(0x2&pageTableEntry)!=0;
			boolean pageIsReadWrite=tableIsReadWrite||directoryReadWrite;
			if (tableIsUser)
				pageIsReadWrite=tableIsReadWrite&&directoryReadWrite;
			//catch write protection violations
			//user page
			if (tableIsUser && directoryIsUser)
			{
				//write protected
				if (!pageIsReadWrite)
				{
					//is the supervisor prevented from writing to a user page?
					if (isSupervisor)
					{
						if (writeProtectUserPages)
						{
							panic("protection violation ws");
							throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WS_PROTECTION);
						}
					}
					//user is writing to a write protected page
					else
					{
						panic("protection violation wu");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
					}
				}
			}
			//supervisor page
			else
			{
				//is a user trying to write to a supervisor page?
				if (pageIsReadWrite)
				{
					if (!isSupervisor)
					{
						panic("protection violation wu");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
					}
				}
				//trying to write to a write protected supervisor page
				else
				{
					if (isSupervisor)
					{
						panic("protection violation ws");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WS_PROTECTION);
					}
					else
					{
						panic("protection violation wu");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
					}
				}
			}

			//set bits 17 and 18 to 1
			if ((pageTableEntry&0x60)!=0)
			{
				pageTableEntry|=0x60;
				computer.physicalMemory.setDoubleWord(pageTableEntryAddress, pageTableEntry);
			}
			int physicalBaseAddress=pageTableEntry&0xfffff000;
			//if page caching is disabled, don't make a TLB entry.  just return the physical base address.
			if (!pageCacheEnabled)
				return physicalBaseAddress|offset;
	
			//save the entry in the TLB
			if (isSupervisor)
				writeSupervisorPageTable[virtualPageIndex]=new PageTableEntry(physicalBaseAddress);
			else
				writeUserPageTable[virtualPageIndex]=new PageTableEntry(physicalBaseAddress);
			return physicalBaseAddress|offset;
		}
		else
		{
			if (!directoryIsUser && !isSupervisor)
			{
				panic("User is trying to write a supervisor directory entry");
				throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
			}
			//catch write protection violations
			//user pages
			if (directoryIsUser)
			{
				//is it write protected?
				if(!directoryReadWrite)
				{
					//supervisor can't write if user pages are write protected 
					if(isSupervisor)
					{
						if (writeProtectUserPages)
						{
							panic("protection violation ws");
							throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WS_PROTECTION);
						}
					}
					//user writing to a write protected page
					else
					{
						panic("protection violation wu");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
					}
				}
			}
			else
			{
				//user writing to a supervisor page
				if (directoryReadWrite)
				{
					if (!isSupervisor)
					{
						panic("protection violation wu");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
					}
				}
				//writing to a write protected supervisor page
				else
				{
					if (!isSupervisor)
					{
						panic("protection violation wu");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WU_PROTECTION);
					}
					else
					{
						panic("protection violation ws");
						throw new Processor.Processor_Exception(computer.processor.PAGE_FAULT,PF_WS_PROTECTION);
					}
				}
			}

			if ((directoryInformation&0x60)==0)
			{
				directoryInformation|=0x60;
				computer.physicalMemory.setDoubleWord(directoryAddress, directoryInformation);
			}
			int physicalBaseAddress=0xffc00000&directoryInformation;
			//don't setup TLB if no cache, just return address
			if (!pageCacheEnabled)
				return physicalBaseAddress|(virtualAddress&0x3fffff);
			int pageSizeIndex=(0xffc00000&virtualAddress)>>>12;
			//create 1Ms worth of TLB entries
			for (int i=0; i<1024; i++)
			{
				pageSize[pageSizeIndex]=true;
				if (isSupervisor)
				{
					if (writeSupervisorPageTable==null)
						writeSupervisorPageTable=new PageTableEntry[PAGES];
					writeSupervisorPageTable[pageSizeIndex]=new PageTableEntry(physicalBaseAddress);
					pageSizeIndex++;
					physicalBaseAddress+=BLOCK_SIZE;
				}
				else
				{
					if (writeUserPageTable==null)
						writeUserPageTable=new PageTableEntry[PAGES];
					writeUserPageTable[pageSizeIndex]=new PageTableEntry(physicalBaseAddress);
					pageSizeIndex++;
					physicalBaseAddress+=BLOCK_SIZE;
				}
			}
			if (isSupervisor)
				return writeSupervisorPageTable[virtualPageIndex].physicalBaseAddress|offset;
			else
				return writeUserPageTable[virtualPageIndex].physicalBaseAddress|offset;
		}
	}
	
	public void setSupervisor(boolean value)
	{
		isSupervisor=value;
	}
	public void setPagingEnabled(boolean value)
	{
		pagingDisabled=!value;
		flush();
	}
	public void setPageCacheEnabled(boolean value)
	{
		pageCacheEnabled=value;
	}
	public void setPageSizeExtensionsEnabled(boolean value)
	{
		pageSizeExtensions=value;
		flush();
	}
	//global pages are not purged from the TLB when the page directory base address is changed
	//right now, just ignore them
	public void setGlobalPagesEnabled(boolean value)
	{
		if (!globalPagesEnabled)
		{
			globalPagesEnabled=value;
			flush();
		}
//		panic("implement global pages");
	}
	public void setWriteProtectUserPages(boolean value)
	{
		if (value)
		{
			//clear out supervisor TLB to make sure we don't violate write protections
			if (writeSupervisorPageTable!=null)
				for (int i=0; i<PAGES; i++)
					writeSupervisorPageTable[i]=null;
//			panic("implement write protect user pages");
		}
		writeProtectUserPages=value;
	}
	//sets the location of the page table directory in physical memory
	public void setPageDirectoryBaseAddress(int address)
	{
		directoryBaseAddress=address&0xfffff000;
		if (globalPagesEnabled)
			nonGlobalFlush();
		else
			flush();
	}
	//obliterate the TLB page tables
	public void flush()
	{
		readUserPageTable=null;
		readSupervisorPageTable=null;
		writeUserPageTable=null;
		writeSupervisorPageTable=null;

		//set all the pages back to 4k size pages
		pageSize=new boolean[PAGES];
		for (int i=0; i<PAGES; i++)
			pageSize[i]=false;
		
		nonGlobalPageList.clear();
	}
	//task switch
	//eliminate all TLB page table entries marked as non global
	private void nonGlobalFlush()
	{
		for (Integer value: nonGlobalPageList)
		{
			//remove it from the four TLB tables (if present)
			int index=value.intValue();
			if (readSupervisorPageTable!=null)
				readSupervisorPageTable[index]=null;
			if (writeSupervisorPageTable!=null)
				writeSupervisorPageTable[index]=null;
			if (readUserPageTable!=null)
				readUserPageTable[index]=null;
			if (writeUserPageTable!=null)
				writeUserPageTable[index]=null;
			//set it back to a 4k page
			pageSize[index]=false;
		}
		nonGlobalPageList.clear();
	}
	private void panic(String message)
	{
		System.out.println("PANIC in linear memory at "+computer.icount+": "+message);
		//System.exit(0);
	}
}

/*

import java.util.HashSet;
import java.util.Set;

import simulator.Processor.Processor_Exception;

public class LinearMemory implements MemoryDevice 
{
	Computer computer;
	
	private byte[] pageSize;
	private final Set<Integer> nonGlobalPages;
	public int baseAddress,lastAddress;
	public boolean isSupervisor,globalPagesEnabled,pagingDisabled,writeProtectUserPages,pageSizeExtensions,pageCacheEnabled;
	
	private static final byte FOUR_K=(byte)0;
	private static final byte FOUR_M=(byte)1;
	
	private static final int BLOCK_SIZE=4*1024;
	private static final int BLOCK_MASK=BLOCK_SIZE-1;
	private static final int INDEX_MASK=~(BLOCK_MASK);
	private static final int INDEX_SHIFT=12;
	private static final int INDEX_SIZE=1<<(32-INDEX_SHIFT);
	
	private MemoryDevice[] readUserIndex, readSupervisorIndex, writeUserIndex, writeSupervisorIndex, readIndex, writeIndex;
	
	public LinearMemory(Computer computer)
	{
		this.computer=computer;
		baseAddress=0;
		lastAddress=0;
		pagingDisabled=true;
		globalPagesEnabled=false;
		writeProtectUserPages=false;
		pageSizeExtensions=false;
		
		nonGlobalPages=new HashSet<Integer>();
		
		pageSize=new byte[INDEX_SIZE];
		for (int i=0; i<INDEX_SIZE; i++)
			pageSize[i]=FOUR_K;
	}

	private MemoryDevice[] createReadIndex()
	{
		if (isSupervisor)
			return(readIndex=readSupervisorIndex=new MemoryDevice[INDEX_SIZE]);
		else
			return(readIndex=readUserIndex=new MemoryDevice[INDEX_SIZE]);
	}

	private MemoryDevice[] createWriteIndex()
	{
		if (isSupervisor)
			return(writeIndex=writeSupervisorIndex=new MemoryDevice[INDEX_SIZE]);
		else
			return(writeIndex=writeUserIndex=new MemoryDevice[INDEX_SIZE]);
	}
	
	private void setReadIndexValue(int index, MemoryDevice value)
	{
		if (readIndex==null)
			createReadIndex()[index]=value;
		else
			readIndex[index]=value;
	}
	private void setWriteIndexValue(int index, MemoryDevice value)
	{
		if (writeIndex==null)
			createWriteIndex()[index]=value;
		else
			writeIndex[index]=value;
	}
	private MemoryDevice getReadIndexValue(int index)
	{
		if (readIndex==null)
			return createReadIndex()[index];
		else
			return readIndex[index];
	}
	private MemoryDevice getWriteIndexValue(int index)
	{
		if (writeIndex==null)
			return createWriteIndex()[index];
		else
			return writeIndex[index];
	}
	
	public void setSupervisor(boolean value)
	{
		isSupervisor=value;
		if (isSupervisor)
		{
			readIndex=readSupervisorIndex;
			writeIndex=writeSupervisorIndex;
		}
		else
		{
			readIndex=readUserIndex;
			writeIndex=writeUserIndex;
		}
	}
	
	public void setPagingEnabled(boolean value)
	{
		pagingDisabled=!value;
		flush();
	}
	public void setPageCacheEnabled(boolean value)
	{
		pageCacheEnabled=value;
	}
	public void setPageSizeExtensionsEnabled(boolean value)
	{
		pageSizeExtensions=value;
		flush();
	}
	public void setGlobalPagesEnabled(boolean value)
	{
		if (!globalPagesEnabled)
		{
			globalPagesEnabled=value;
			flush();
		}
	}
	public void setWriteProtectUserPages(boolean value)
	{
		if (value)
		{
			if (writeSupervisorIndex!=null)
				for (int i=0; i<INDEX_SIZE; i++)
					nullIndex(writeSupervisorIndex,i);
		}
		writeProtectUserPages=value;
	}
	public void flush()
	{
		for (int i=0; i<INDEX_SIZE; i++)
			pageSize[i]=FOUR_K;
		nonGlobalPages.clear();
		readUserIndex=null;
		readSupervisorIndex=null;
		writeUserIndex=null;
		writeSupervisorIndex=null;
	}
	private void nullIndex(MemoryDevice[] array, int index)
	{
		if (array!=null)
			array[index]=null;
	}
	private void partialFlush()
	{
		if (globalPagesEnabled)
		{
			for (Integer value: nonGlobalPages)
			{
				int index=value.intValue();
				nullIndex(readSupervisorIndex,index);
				nullIndex(writeSupervisorIndex,index);
				nullIndex(readUserIndex,index);
				nullIndex(writeUserIndex,index);
				pageSize[index]=FOUR_K;
			}
			nonGlobalPages.clear();
		}
		else
			flush();
	}
	public void setPageDirectoryBaseAddress(int address)
	{
		baseAddress=address&0xfffff000;
		partialFlush();
	}
	public void invalidateTLBEntry(int offset)
	{
		int index=offset>>>INDEX_SHIFT;
		if (pageSize[index]==FOUR_K)
		{
			nullIndex(readSupervisorIndex,index);
			nullIndex(writeSupervisorIndex,index);
			nullIndex(readUserIndex,index);
			nullIndex(writeUserIndex,index);
			nonGlobalPages.remove(Integer.valueOf(index));
		}
		else
		{
			index&=0xffc00;
			for (int i=0; i<1024; i++,index++)
			{
				nullIndex(readSupervisorIndex,index);
				nullIndex(writeSupervisorIndex,index);
				nullIndex(readUserIndex,index);
				nullIndex(writeUserIndex,index);
				nonGlobalPages.remove(Integer.valueOf(index));				
			}
		}
	}
	
	private class PhysicalBlock implements MemoryDevice
	{
		int base;
		public PhysicalBlock(int base)
		{
			this.base=base;
		}
		public byte getByte(int address) 
		{
			return computer.physicalMemory.getByte(base+address);
		}
		public int getDoubleWord(int address) 
		{
			return computer.physicalMemory.getDoubleWord(base+address);
		}
		public long getQuadWord(int address) 
		{
			return computer.physicalMemory.getQuadWord(base+address);
		}
		public short getWord(int address) 
		{
			return computer.physicalMemory.getWord(base+address);
		}
		public void setByte(int address, byte value) 
		{
			computer.physicalMemory.setByte(base+address, value);
		}
		public void setDoubleWord(int address, int value) 
		{
			computer.physicalMemory.setDoubleWord(base+address, value);
		}
		public void setQuadWord(int address, long value) 
		{
			computer.physicalMemory.setQuadWord(base+address, value);
		}
		public void setWord(int address, short value) 
		{
			computer.physicalMemory.setWord(base+address, value);
		}
		
	}
	
	private MemoryDevice validateTLBEntryRead(int offset)
	{
		System.out.println("validate TLB entry read "+ offset);
		int idx=offset>>>INDEX_SHIFT;
		if (pagingDisabled)
		{
			setReadIndexValue(idx,new PhysicalBlock(offset));
			return readIndex[idx];
		}
		lastAddress=offset;
		int directoryAddress=baseAddress|(0xffc&(offset>>>20));
		int directoryRawBits=computer.physicalMemory.getDoubleWord(directoryAddress);
		boolean directoryPresent=(0x1 & directoryRawBits)!=0;
		if (!directoryPresent)
		{
			if (isSupervisor)
				panic("not present rs");
			else
				panic("not present ru");
		}
		boolean directoryGlobal=globalPagesEnabled&&((0x100&directoryRawBits)!=0);
		boolean directoryUser=(0x4&directoryRawBits)!=0;
		boolean directoryIs4MegPage=((0x80&directoryRawBits)!=0)&&pageSizeExtensions;
		if (directoryIs4MegPage)
		{
			if (!directoryUser&&!isSupervisor)
				panic("protection violation ru");
			if ((directoryRawBits&0x20)==0)
			{
				directoryRawBits|=0x20;
				computer.physicalMemory.setDoubleWord(directoryAddress,directoryRawBits);
			}
			int fourMegPageStartAddress=0xffc00000&directoryRawBits;
			if (!pageCacheEnabled)
				return new PhysicalBlock(fourMegPageStartAddress|(offset&0x3fffff));
			int tableIndex=(0xffc00000&offset)>>>12;
			for (int i=0; i<1024; i++)
			{
				MemoryDevice m=new PhysicalBlock(fourMegPageStartAddress);
				fourMegPageStartAddress+=BLOCK_SIZE;
				pageSize[tableIndex]=FOUR_M;
				setReadIndexValue(tableIndex++,m);
				if (directoryGlobal)
					continue;
				nonGlobalPages.add(Integer.valueOf(i));
			}
			return readIndex[idx];
		}
		else
		{
			int directoryBaseAddress=directoryRawBits&0xfffff000;
			int tableAddress=directoryBaseAddress|((offset>>>10)&0xffc);
			int tableRawBits=computer.physicalMemory.getDoubleWord(tableAddress);
			boolean tablePresent=(0x1&tableRawBits)!=0;
			if (!tablePresent)
			{
				if (isSupervisor)
					panic("not present rs");
				else
					panic("not present ru");
			}
			boolean tableGlobal=globalPagesEnabled&&((0x100&tableRawBits)!=0);
			boolean tableUser=(0x4&tableRawBits)!=0;
			boolean pageIsUser=tableUser&&directoryUser;
			if (!pageIsUser&&!isSupervisor)
				panic("protection violation ru");
			if ((tableRawBits&0x20)==0)
			{
				tableRawBits|=0x20;
				computer.physicalMemory.setDoubleWord(tableAddress,tableRawBits);
			}
			int fourKStartAddress=tableRawBits&0xfffff000;
			if (!pageCacheEnabled)
				return new PhysicalBlock(fourKStartAddress);
			pageSize[idx]=FOUR_K;
			if (!tableGlobal)
				nonGlobalPages.add(Integer.valueOf(idx));
			setReadIndexValue(idx,new PhysicalBlock(fourKStartAddress));
			return readIndex[idx];
		}
	}

	private MemoryDevice validateTLBEntryWrite(int offset)
	{
		System.out.println("validate TLB entry write "+ offset);
		int idx=offset>>>INDEX_SHIFT;
		if (pagingDisabled)
		{
			setWriteIndexValue(idx,new PhysicalBlock(offset));
			return writeIndex[idx];
		}
		lastAddress=offset;
		int directoryAddress=baseAddress|(0xffc&(offset>>>20));
		int directoryRawBits=computer.physicalMemory.getDoubleWord(directoryAddress);
		boolean directoryPresent=(0x1 & directoryRawBits)!=0;
		if (!directoryPresent)
		{
			if (isSupervisor)
				panic("not present ws");
			else
				panic("not present wu");
		}
		boolean directoryGlobal=globalPagesEnabled&&((0x100&directoryRawBits)!=0);
		boolean directoryUser=(0x4&directoryRawBits)!=0;
		boolean directoryReadWrite=(0x2&directoryRawBits)!=0;
		boolean directoryIs4MegPage=((0x80&directoryRawBits)!=0)&&pageSizeExtensions;
		if (directoryIs4MegPage)
		{
			if (directoryUser)
			{
				if(!directoryReadWrite)
				{
					if(isSupervisor)
					{
						if (writeProtectUserPages)
							panic("protection violation ws");
					}
					else
						panic("protection violation wu");
				}
			}
			else
			{
				if (directoryReadWrite)
				{
					if (!isSupervisor)
						panic("protection violation wu");
				}
				else
				{
					if (!isSupervisor)
						panic("protection violation wu");
					else
						panic("protection violation ws");
				}
			}
			
			if ((directoryRawBits&0x60)!=0x60)
			{
				directoryRawBits|=0x60;
				computer.physicalMemory.setDoubleWord(directoryAddress,directoryRawBits);
			}
			int fourMegPageStartAddress=0xffc00000&directoryRawBits;
			if (!pageCacheEnabled)
				return new PhysicalBlock(fourMegPageStartAddress|(offset&0x3fffff));
			int tableIndex=(0xffc00000&offset)>>>12;
			for (int i=0; i<1024; i++)
			{
				MemoryDevice m=new PhysicalBlock(fourMegPageStartAddress);
				fourMegPageStartAddress+=BLOCK_SIZE;
				pageSize[tableIndex]=FOUR_M;
				setWriteIndexValue(tableIndex++,m);
				if (directoryGlobal)
					continue;
				nonGlobalPages.add(Integer.valueOf(i));
			}
			return writeIndex[idx];
		}
		else
		{
			int directoryBaseAddress=directoryRawBits&0xfffff000;
			int tableAddress=directoryBaseAddress|((offset>>>10)&0xffc);
			int tableRawBits=computer.physicalMemory.getDoubleWord(tableAddress);
			boolean tablePresent=(0x1&tableRawBits)!=0;
			if (!tablePresent)
			{
				if (isSupervisor)
					panic("not present ws");
				else
					panic("not present wu");
			}
			boolean tableGlobal=globalPagesEnabled&&((0x100&tableRawBits)!=0);
			boolean tableReadWrite=(0x2&tableRawBits)!=0;
			boolean tableUser=(0x4&tableRawBits)!=0;
			boolean pageIsUser=tableUser&&directoryUser;
			boolean pageIsReadWrite=tableReadWrite||directoryReadWrite;			
			if (pageIsUser)
				pageIsReadWrite=tableReadWrite&&directoryReadWrite;
			if (pageIsUser)
			{
				if (!pageIsReadWrite)
				{
					if (isSupervisor)
					{
						if (writeProtectUserPages)
							panic("protection violation ws");
					}
					else
						panic("protection violation wu");
				}
			}
			else
			{
				if (pageIsReadWrite)
				{
					if (!isSupervisor)
						panic("protection violation wu");
				}
				else
				{
					if (isSupervisor)
						panic("protection violation ws");
					else
						panic("protection violation wu");
				}
			}
			if ((tableRawBits&0x60)!=0x60)
			{
				tableRawBits|=0x60;
				computer.physicalMemory.setDoubleWord(tableAddress,tableRawBits);
			}
			int fourKStartAddress=tableRawBits&0xfffff000;
			if (!pageCacheEnabled)
				return new PhysicalBlock(fourKStartAddress);
			pageSize[idx]=FOUR_K;
			if (!tableGlobal)
				nonGlobalPages.add(Integer.valueOf(idx));
			setWriteIndexValue(idx,new PhysicalBlock(fourKStartAddress));
			return writeIndex[idx];
		}
	}

	private void panic(String message)
	{
		System.out.println("PANIC in linear memory: "+message);
		System.exit(0);
	}
	
	public byte getByte(int address) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			return getReadIndexValue(offset).getByte(address & BLOCK_MASK);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		return validateTLBEntryRead(address).getByte(address&BLOCK_MASK);
	}
	public short getWord(int address) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			return getReadIndexValue(offset).getWord(address & BLOCK_MASK);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		MemoryDevice m=validateTLBEntryRead(address);
		return validateTLBEntryRead(address).getWord(address&BLOCK_MASK);
	}
	
	public int getDoubleWord(int address) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			return getReadIndexValue(offset).getDoubleWord(address & BLOCK_MASK);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		MemoryDevice m=validateTLBEntryRead(address);
		return validateTLBEntryRead(address).getDoubleWord(address&BLOCK_MASK);
	}

	public long getQuadWord(int address) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			return getReadIndexValue(offset).getQuadWord(address & BLOCK_MASK);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		MemoryDevice m=validateTLBEntryRead(address);
		return validateTLBEntryRead(address).getQuadWord(address&BLOCK_MASK);
	}

	public void setByte(int address, byte value) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			getWriteIndexValue(offset).setByte(address & BLOCK_MASK,value);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		validateTLBEntryWrite(address).setByte(address&BLOCK_MASK,value);
	}

	public void setDoubleWord(int address, int value) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			getWriteIndexValue(offset).setDoubleWord(address & BLOCK_MASK,value);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		validateTLBEntryWrite(address).setDoubleWord(address&BLOCK_MASK,value);
	}

	public void setQuadWord(int address, long value) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			getWriteIndexValue(offset).setQuadWord(address & BLOCK_MASK,value);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		validateTLBEntryWrite(address).setQuadWord(address&BLOCK_MASK,value);
	}

	public void setWord(int address, short value) 
	{
		try
		{
			int offset=address>>>INDEX_SHIFT;
			getWriteIndexValue(offset).setWord(address & BLOCK_MASK,value);
		}
		catch(NullPointerException e){}
		catch(Processor_Exception e){}
		validateTLBEntryWrite(address).setWord(address&BLOCK_MASK,value);
	}
}*/
