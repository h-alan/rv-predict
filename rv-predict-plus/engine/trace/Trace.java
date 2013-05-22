package trace;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.Vector;

public class Trace {

	Vector<AbstractNode> fulltrace = new Vector<AbstractNode>();
	
	HashMap<Long, Long> nodeGIDTidMap = new HashMap<Long, Long>();
	
	HashMap<Long,Vector<AbstractNode>> threadNodesMap = new HashMap<Long,Vector<AbstractNode>>();
	HashMap<Long, AbstractNode> threadFirstNodeMap = new HashMap<Long, AbstractNode>();
	HashMap<Long, AbstractNode> threadLastNodeMap = new HashMap<Long, AbstractNode>();
	
	HashMap<Long,HashMap<String,Vector<LockPair>>> threadIndexedLockPairs = new HashMap<Long,HashMap<String,Vector<LockPair>>>();
	HashMap<Long,Stack<ISyncNode>> threadSyncStack = new HashMap<Long,Stack<ISyncNode>>();

	
	HashMap<Long,Vector<BranchNode>> threadBranchNodes = new HashMap<Long,Vector<BranchNode>>();
	HashMap<Long,Vector<BBNode>> threadBBNodes = new HashMap<Long,Vector<BBNode>>();
	
	HashMap<String,Vector<ISyncNode>> syncNodesMap = new HashMap<String,Vector<ISyncNode>>();	

	HashMap<String,Vector<ReadNode>> indexedReadNodes = new HashMap<String,Vector<ReadNode>>();

	HashMap<String,HashMap<Long,Vector<IMemNode>>> indexedThreadReadWriteNodes = new HashMap<String,HashMap<Long,Vector<IMemNode>>>();

	HashMap<String,Vector<WriteNode>> indexedWriteNodes = new
			HashMap<String,Vector<WriteNode>>();
	
	HashMap<Long, String> threadIdNamemap;
	HashMap<Integer, String> sharedVarIdSigMap;
	HashMap<Integer, String> stmtIdSigMap;
	
	public Vector<AbstractNode> getFullTrace()
	{
		return fulltrace;
	}
	public HashMap<Long, Long> getNodeGIDTIdMap() {
		return nodeGIDTidMap;
	}
	public void setSharedVarIdSigMap(HashMap<Integer, String> map) {
		
		sharedVarIdSigMap = map;
	}
	public void setStmtIdSigMap(HashMap<Integer, String> map) {
		
		stmtIdSigMap = map;
	}
	public void setThreadIdNameMap(HashMap<Long, String> map)
	{
		threadIdNamemap = map;
	}
	public HashMap<Integer, String> getSharedVarIdMap() {
		
		return sharedVarIdSigMap;
	}
	public HashMap<Integer, String> getStmtSigIdMap() {
		
		return stmtIdSigMap;
	}
	public HashMap<Long, String> getThreadIdNameMap()
	{
		return threadIdNamemap;
	}

	
	public HashMap<Long, AbstractNode> getThreadFirstNodeMap()
	{
		return threadFirstNodeMap;
	}
	public HashMap<Long, AbstractNode> getThreadLastNodeMap()
	{
		return threadLastNodeMap;
	}
	public HashMap<Long,Vector<AbstractNode>> getThreadNodesMap()
	{
		return threadNodesMap;
	}
	public HashMap<String,Vector<ISyncNode>> getSyncNodesMap()
	{
		return syncNodesMap;
	}
	public HashMap<Long,HashMap<String,Vector<LockPair>>> getThreadIndexedLockPairs()
	{
		return threadIndexedLockPairs;
	}
	public HashMap<String,Vector<ReadNode>> getIndexedReadNodes()
	{
		return indexedReadNodes;
	}
	public HashMap<String,Vector<WriteNode>> getIndexedWriteNodes()
	{
		return indexedWriteNodes;
	}
	public HashMap<String,HashMap<Long,Vector<IMemNode>>> getIndexedThreadReadWriteNodes()
	{
		return indexedThreadReadWriteNodes;
	}
	public Vector<ReadNode> getDependentReadNodes(IMemNode rnode) {
		
		Vector<ReadNode> readnodes = new Vector<ReadNode>();
		long tid = rnode.getTid();
		long POS = rnode.getGID();
		long pos = -1;
		Vector<BranchNode> branchNodes = threadBranchNodes.get(tid);
		if(branchNodes!=null)
		//TODO: improve to log(n) complexity
		for(int i =0;i<branchNodes.size();i++)
		{
			long id = branchNodes.get(i).getGID();
			if(id>POS)
				break;
			else
				pos =id;
		}
		
		if(pos>=0)
		{
			Vector<AbstractNode> nodes = threadNodesMap.get(tid);//TODO: optimize here to check only READ node 
			for(int i =0;i<nodes.size();i++)
			{
				AbstractNode node = nodes.get(i);
				if(node.getGID()>pos)
					break;
				else
				{
					if(node instanceof ReadNode)
						readnodes.add((ReadNode) node);
				}
			}
		}
		
		return readnodes;
	}
	
	public void addNode(AbstractNode node)
	{
		Long tid = node.getTid();
		
		if(node instanceof BBNode)
		{
			Vector<BBNode> bbnodes = threadBBNodes.get(tid);
			if(bbnodes == null)
			{
				bbnodes = new Vector<BBNode>();
				threadBBNodes.put(tid, bbnodes);
			}
			bbnodes.add((BBNode)node);
		}
		else if(node instanceof BranchNode)
		{
			Vector<BranchNode> branchnodes = threadBranchNodes.get(tid);
			if(branchnodes == null)
			{
				branchnodes = new Vector<BranchNode>();
				threadBranchNodes.put(tid, branchnodes);
			}
			branchnodes.add((BranchNode)node);
		}
		else
		{
			fulltrace.add(node);

			nodeGIDTidMap.put(node.getGID(), node.getTid());
			
			Vector<AbstractNode> threadNodes = threadNodesMap.get(tid);
			if(threadNodes ==null)
			{
				threadNodes = new Vector<AbstractNode>();
				threadNodesMap.put(tid, threadNodes);
				threadFirstNodeMap.put(tid, node);
				
			}
			
			threadNodes.add(node);
			
			//TODO: Optimize it
			threadLastNodeMap.put(tid, node); 
			if(node instanceof IMemNode)
			{
				String addr = ((IMemNode)node).getAddr();

				HashMap<Long, Vector<IMemNode>> threadReadWriteNodes = indexedThreadReadWriteNodes.get(addr);
				if(threadReadWriteNodes==null)
				{
					threadReadWriteNodes = new HashMap<Long, Vector<IMemNode>>();
					indexedThreadReadWriteNodes.put(addr, threadReadWriteNodes);
				}
				Vector<IMemNode> rwnodes = threadReadWriteNodes.get(tid);
				if(rwnodes==null)
				{
					rwnodes =  new Vector<IMemNode>();
					threadReadWriteNodes.put(tid, rwnodes);
				}
				rwnodes.add((IMemNode)node);
				
				if(node instanceof ReadNode)
				{
					
					Vector<ReadNode> readNodes = indexedReadNodes.get(addr);
					if(readNodes == null)
					{
						readNodes =  new Vector<ReadNode>();
						indexedReadNodes.put(addr, readNodes);
					}
					readNodes.add((ReadNode)node);
					
				}
				else //write node
				{
					Vector<WriteNode> writeNodes = indexedWriteNodes.get(addr);
					if(writeNodes ==null)
					{
						writeNodes = new Vector<WriteNode>();
						indexedWriteNodes.put(addr, writeNodes);
					}
					writeNodes.add((WriteNode)node);
				}
			}
			else
			{
				String addr = ((ISyncNode)node).getAddr();
				Vector<ISyncNode> syncNodes = syncNodesMap.get(addr);
				if(syncNodes==null)
				{
					syncNodes = new Vector<ISyncNode>();
					syncNodesMap.put(addr, syncNodes);
				}
				
				syncNodes.add((ISyncNode)node);
				
				
				if (node instanceof LockNode)
				{
					Stack<ISyncNode> stack = threadSyncStack.get(tid);
					if(stack==null)
					{
						stack = new Stack<ISyncNode>();
						threadSyncStack.put(tid, stack);
					}
					
					stack.push((LockNode)node);					
				}
				else if (node instanceof UnlockNode)
				{
					HashMap<String,Vector<LockPair>> indexedLockpairs = threadIndexedLockPairs.get(tid);
					if(indexedLockpairs==null)
					{
						indexedLockpairs = new HashMap<String,Vector<LockPair>>();
						threadIndexedLockPairs.put(tid, indexedLockpairs);
					}					
					Vector<LockPair> lockpairs = indexedLockpairs.get(addr);
					if(lockpairs==null)
					{
						lockpairs = new Vector<LockPair>();
						indexedLockpairs.put(addr, lockpairs);
					}
					
					Stack<ISyncNode> stack = threadSyncStack.get(tid);
					if(stack==null)
					{
						stack = new Stack<ISyncNode>();
						threadSyncStack.put(tid, stack);
					}
					//assert(stack.size()>0); //this is possible when segmented
					if(stack.size()==0)
						lockpairs.add(new LockPair(null,(UnlockNode)node));
					else if(stack.size()==1)
						lockpairs.add(new LockPair(stack.pop(),(UnlockNode)node));
					else 
						stack.pop();//handle reentrant lock
				}
			}
		}
	}
	
	public void finishedLoading()
	{
		//check threadSyncStack - only to handle when segmented
		Iterator<Entry<Long,Stack<ISyncNode>>> entryIt = threadSyncStack.entrySet().iterator();
		while(entryIt.hasNext())
		{
			Entry<Long,Stack<ISyncNode>> entry = entryIt.next();
			Long tid = entry.getKey();
			Stack<ISyncNode> stack = entry.getValue();
			
			if(!stack.isEmpty())
			{
				HashMap<String,Vector<LockPair>> indexedLockpairs = threadIndexedLockPairs.get(tid);
				if(indexedLockpairs==null)
				{
					indexedLockpairs = new HashMap<String,Vector<LockPair>>();
					threadIndexedLockPairs.put(tid, indexedLockpairs);
				}
				
				while(!stack.isEmpty())
				{
					ISyncNode syncnode = stack.pop();//lock or wait
										
					Vector<LockPair> lockpairs = indexedLockpairs.get(syncnode.getAddr());
					if(lockpairs==null)
					{
						lockpairs = new Vector<LockPair>();
						indexedLockpairs.put(syncnode.getAddr(), lockpairs);
					}
					
					lockpairs.add(new LockPair(syncnode,null));
				}
			}
		}
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// TODO Auto-generated method stub

		
	}



}
