package z3;

import trace.AbstractNode;
import trace.IMemNode;
import trace.ISyncNode;
import trace.JoinNode;
import trace.LockNode;
import trace.LockPair;
import trace.NotifyNode;
import trace.ReadNode;
import trace.StartNode;
import trace.Trace;
import trace.UnlockNode;
import trace.WaitNode;
import trace.WriteNode;

import java.io.BufferedReader;

import java.io.IOException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Stack;
import java.util.Vector;
import java.util.Map.Entry;

public class Z3Engine
{
	private int id =0;
	private String appname;
	public Z3Run task;
	
	private StringBuilder CONS_DECLARE;
	private StringBuilder CONS_ASSERT;
	private final StringBuilder CONS_GETMODEL = new StringBuilder("(check-sat)\n(get-model)\n(exit)");

	public Z3Engine(String appname)
	{
		this.appname = appname;
		this.id = 0;
	}
	private static String makeVariable(long GID)
	{
		return "x"+GID;
	}
	
	public void declareVariables(Vector<AbstractNode> trace)
	{
		CONS_DECLARE = new StringBuilder("");
		CONS_ASSERT = new StringBuilder("");
		
		//CONS_ASSERT = "(assert (distinct ";
		int size = trace.size();
		for(int i=0;i<size;i++)
		{
			AbstractNode node = trace.get(i);
			long GID = node.getGID();
			String var = makeVariable(GID);
			
			CONS_DECLARE.append("(declare-const ").append(var).append(" Int)\n");
		
			//CONS_ASSERT.append(var).append(" ");
			
			CONS_ASSERT.append("(assert (and (> ").append(var).append(" 0) (< ").append(var)
			    .append(" ").append(size+1).append(")))\n");
		}
		
		//CONS_ASSERT.append("))\n");
						
	}
	public void addIntraThreadConstraints(HashMap<Long,Vector<AbstractNode>> map)
	{
		Iterator<Vector<AbstractNode>> mapIt = map.values().iterator();
		while(mapIt.hasNext())
		{
			Vector<AbstractNode> nodes = mapIt.next();
			String lastVar = makeVariable(nodes.get(0).getGID());
			for(int i=1;i<nodes.size();i++)
			{			
				String var = makeVariable(nodes.get(i).getGID());
				CONS_ASSERT.append("(assert (< ").append(lastVar).append(" ").append(var).append("))\n");
				lastVar = var;
			}
		}
	}
	public void addSynchronizationConstraints(Trace trace, HashMap<String,Vector<ISyncNode>> syncNodesMap, 
												HashMap<Long,AbstractNode> firstNodes,
														HashMap<Long,AbstractNode> lastNodes)
	{
		//thread first node - last node
		Iterator<Vector<ISyncNode>> mapIt = syncNodesMap.values().iterator();
		while(mapIt.hasNext())
		{
			Vector<ISyncNode> nodes = mapIt.next();
			
			Vector<LockPair> lockPairs = new Vector<LockPair>();
			
			HashMap<Long,Stack<ISyncNode>> threadSyncStack = new HashMap<Long,Stack<ISyncNode>>();
			NotifyNode matchNotifyNode = null;
			
			//during recording
			//should after wait, before notify
			//after lock, before unlock
			
			for(int i=0;i<nodes.size();i++)
			{			
				ISyncNode node = nodes.get(i);
				String var = makeVariable(node.getGID());
				if(node instanceof StartNode)
				{
					long tid = Long.valueOf(node.getAddr());
					AbstractNode fnode = firstNodes.get(tid);
					if(fnode!=null)
					{
					String fvar = makeVariable(fnode.getGID());
					
					CONS_ASSERT.append("(assert (< ").append(var).append(" ").append(fvar).append("))\n");
					}
				}
				else if (node instanceof JoinNode)
				{
					long tid = Long.valueOf(node.getAddr());
					AbstractNode lnode = lastNodes.get(tid);
					if(lnode!=null)
					{
						String lvar = makeVariable(lnode.getGID());
						CONS_ASSERT.append("(assert (< ").append(lvar).append(" ").append(var).append("))\n");
					}
					
				}
				else if (node instanceof LockNode)
				{
					long tid = node.getTid();
					
					Stack<ISyncNode> stack = threadSyncStack.get(tid);
					if(stack==null)
					{
						stack = new Stack<ISyncNode>();
						threadSyncStack.put(tid, stack);
					}
					
					stack.push(node);					
				}
				else if (node instanceof UnlockNode)
				{
					long tid = node.getTid();
					Stack<ISyncNode> stack = threadSyncStack.get(tid);
					
					//assert(stack.size()>0);//this is possible when segmented
					if(stack==null)
					{
						stack = new Stack<ISyncNode>();
						threadSyncStack.put(tid, stack);
					}
					
					//TODO: make sure no nested locks?

					if(stack.isEmpty())
						lockPairs.add(new LockPair(null,node));
					else if(stack.size()==1)
						lockPairs.add(new LockPair(stack.pop(),node));
					else
						stack.pop();//handle reentrant lock here
					
				}
				else if (node instanceof WaitNode)
				{
					long tid = node.getTid();

					//assert(matchNotifyNode!=null);this is also possible when segmented
					if(matchNotifyNode!=null)
					{
						String notifyVar = makeVariable(matchNotifyNode.getGID());
						
	
						int nodeIndex = trace.getFullTrace().indexOf(node)+1;
						
						try{					
							//TODO: handle OutofBounds
	
						while(trace.getFullTrace().get(nodeIndex).getTid()!=tid)
							nodeIndex++;
								
						var = makeVariable(trace.getFullTrace().get(nodeIndex).getGID());
						
						CONS_ASSERT.append("(assert (< ").append(notifyVar).append(" ").append(var).append("))\n");
						}catch(Exception e)
						{
							e.printStackTrace();
						}
						
						//clear notifyNode
						matchNotifyNode=null;
					}
					
					Stack<ISyncNode> stack = threadSyncStack.get(tid);
					//assert(stack.size()>0);
					if(stack==null)
					{
						stack = new Stack<ISyncNode>();
						threadSyncStack.put(tid, stack);
					}
					if(stack.isEmpty())
						lockPairs.add(new LockPair(null,node));
					else if(stack.size()==1)
						lockPairs.add(new LockPair(stack.pop(),node));
					else
						stack.pop();//handle reentrant lock here
					
					stack.push(node);
					

				}
				else if (node instanceof NotifyNode)
				{
					matchNotifyNode = (NotifyNode)node;
				}
			}
			
			
			//check threadSyncStack 
			Iterator<Stack<ISyncNode>> stackIt = threadSyncStack.values().iterator();
			while(stackIt.hasNext())
			{
				Stack<ISyncNode> stack = stackIt.next();
				if(stack.size()>0)//handle reentrant lock here, only pop the first locking node
					lockPairs.add(new LockPair(stack.firstElement(),null));
			}
			CONS_ASSERT.append(constructLockConstraintsOptimized(lockPairs));
			
		}
	}
	private static String constructLockConstraintsOptimized(Vector<LockPair> lockPairs)
	{
		String CONS_LOCK = "";
		
		//obtain each thread's last lockpair
		HashMap<Long,LockPair> lastLockPairMap = new HashMap<Long,LockPair>();
		
		for(int i=0;i<lockPairs.size()-1;i++)
		{
			LockPair lp1 = lockPairs.get(i);
			String var_lp1_a="";
			String var_lp1_b="";
			
			if(lp1.lock==null)//
				continue;
			else
				var_lp1_a = makeVariable(lp1.lock.getGID());
			
			if(lp1.unlock!=null)
				var_lp1_b = makeVariable(lp1.unlock.getGID());

			
			long lp1_tid = lp1.lock.getTid();
		
			
			String cons_a = "";//first lock
			String cons_a_end = "true";
			boolean cons_a_maySatisfy = true;
			
			String cons_b = "";
			String cons_b_end = "false";
			
			for(int j=i+1;j<lockPairs.size();j++)
			{
				LockPair lp2 = lockPairs.get(j);
				
				
				String var_lp2_b="";
				String var_lp2_a="";
				
				if(lp2.unlock==null||
						(lp2.unlock.getTid()==lp1_tid//exclude lockpair by the same thread
							&&lastLockPairMap.get(lp1_tid)!=lp2))//make sure lp2 is not lp1's previous lock-pair 
					continue;
				else
					var_lp2_b = makeVariable(lp2.unlock.getGID());
				
				if(lp2.lock!=null)
					var_lp2_a = makeVariable(lp2.lock.getGID());

				if(lp2.lock!=null&&lp1.unlock!=null
						&&lastLockPairMap.get(lp1_tid)!=lp2)
				{
					cons_a= "(and (> "+var_lp2_a+" "+var_lp1_b+")\n" + cons_a;
					cons_a_end +=")";
				}
				else
					cons_a_maySatisfy = false;
				
				String cons_b_ = "";				

				//if lp2 is pre of lp1, remove redundant constraint
				if(lastLockPairMap.get(lp1_tid)!=lp2)
				{
					cons_b_ = "(and (> "+var_lp1_a+" "+var_lp2_b+")\n";	//must hold here
					cons_b_end +=")";
				}
				

				
				String cons_c = "";	
				String cons_c_end = "true";
				
				for(int k=0;k<lockPairs.size();k++)
				{
					if(k!=i&&k!=j)
					{
						LockPair lp3 = lockPairs.get(k);
						
						String var_lp3_a="";
						String var_lp3_b="";
						
						long lp3_tid = lp1.lock.getTid();
						
						if(lp3.lock!=null)
						{
							var_lp3_a = makeVariable(lp3.lock.getGID());
							lp3_tid = lp3.lock.getTid();
						}
						if(lp3.unlock!=null)
						{
							var_lp3_b = makeVariable(lp3.unlock.getGID());
							lp3_tid = lp3.unlock.getTid();
						}
						
						//exclude lockpairs by the same thread except the previous one
						if(lp3_tid==lp1_tid&&lastLockPairMap.get(lp1_tid)!=lp3)
							continue;
						
						
						String cons_d = "(and ";
						
						if(lp3.lock==null)//then the unlock node must be the first one
						{
							if(lp2.lock!=null)
								cons_d +="(> " +var_lp2_a+" "+var_lp3_b+")\n";
							else cons_d +="true\n";
						}
						else if(lp3.unlock==null)//then the lock node must be the last one
						{
							cons_d +="(> "+var_lp3_a+" "+var_lp1_b+")\n";
						}
						else
						{
							if(lp2.lock!=null)
								cons_d += "(or (> "+var_lp3_a+" "+var_lp1_b+")" +
										" (> " +var_lp2_a+" "+var_lp3_b+"))\n";
							else
								cons_d +="(> "+var_lp3_a+" "+var_lp1_b+")\n";
								
						}
						
						cons_c= cons_d + cons_c;
						cons_c_end +=")";
					}
					
				}
				
				cons_c+=cons_c_end;
				
				cons_b_ = cons_b_ + cons_c + ")\n";

				cons_b += "(or "+cons_b_;
				
			}
			cons_b +=cons_b_end;
			
			
			if(cons_a_maySatisfy)
			{
				cons_a+=cons_a_end+"\n";
				
				CONS_LOCK+="(assert \n(or \n"+cons_a+" "+cons_b+"))\n\n";
			}
			else
				CONS_LOCK+="(assert "+cons_b+")\n\n";
			
			
			lastLockPairMap.put(lp1.lock.getTid(), lp1);

		}
		
		return CONS_LOCK;
		
	}
	private static String constructLockConstraints(Vector<LockPair> lockPairs)
	{
		String CONS_LOCK = "";
		//handle lock pairs
		for(int i=0;i<lockPairs.size()-1;i++)
		{
			LockPair lp1 = lockPairs.get(i);
			String var_lp1_a="";
			String var_lp1_b="";
			
			if(lp1.lock==null)//
				continue;
			else
				var_lp1_a = makeVariable(lp1.lock.getGID());
			
			if(lp1.unlock!=null)
				var_lp1_b = makeVariable(lp1.unlock.getGID());

			String cons_a = "";//first lock
			String cons_a_end = "true";
			boolean cons_a_maySatisfy = true;
			
			String cons_b = "";
			String cons_b_end = "false";
			
			for(int j=i+1;j<lockPairs.size();j++)
			{
				LockPair lp2 = lockPairs.get(j);
				String var_lp2_b="";
				String var_lp2_a="";
				
				if(lp2.unlock==null)
					continue;
				else
					var_lp2_b = makeVariable(lp2.unlock.getGID());
				
				if(lp2.lock!=null)
					var_lp2_a = makeVariable(lp2.lock.getGID());

				if(lp2.lock!=null&&lp1.unlock!=null)
				{
					cons_a= "(and (> "+var_lp2_a+" "+var_lp1_b+")\n" + cons_a;
					cons_a_end +=")";
				}
				else
					cons_a_maySatisfy = false;
				
				String cons_b_ = "(and (> "+var_lp1_a+" "+var_lp2_b+")\n";	//must hold here					

				cons_b_end +=")";

				String cons_c = "";	
				String cons_c_end = "true";
				
				for(int k=0;k<lockPairs.size();k++)
				{
					if(k!=i&&k!=j)
					{
						LockPair lp3 = lockPairs.get(k);
						
						String var_lp3_a="";
						String var_lp3_b="";
						
						
						if(lp3.lock!=null)
							var_lp3_a = makeVariable(lp3.lock.getGID());
						if(lp3.unlock!=null)
							var_lp3_b = makeVariable(lp3.unlock.getGID());
						
						String cons_d = "(and ";
						
						if(lp3.lock==null)//then the unlock node must be the first one
						{
							if(lp2.lock!=null)
								cons_d +="(> " +var_lp2_a+" "+var_lp3_b+")\n";
							else cons_d +="true\n";
						}
						else if(lp3.unlock==null)//then the lock node must be the last one
						{
							cons_d +="(> "+var_lp3_a+" "+var_lp1_b+")\n";
						}
						else
						{
							if(lp2.lock!=null)
								cons_d += "(or (> "+var_lp3_a+" "+var_lp1_b+")" +
										" (> " +var_lp2_a+" "+var_lp3_b+"))\n";
							else
								cons_d +="(> "+var_lp3_a+" "+var_lp1_b+")\n";
								
						}
						
						cons_c= cons_d + cons_c;
						cons_c_end +=")";
					}
					
				}
				
				cons_c+=cons_c_end;
				
				cons_b_ = cons_b_ + cons_c + ")\n";

				cons_b += "(or "+cons_b_;
				
			}
			cons_b +=cons_b_end;
			
			if(cons_a_maySatisfy)
			{
				cons_a+=cons_a_end+"\n";
				
				CONS_LOCK+="(assert \n(or \n"+cons_a+" "+cons_b+"))\n\n";
			}
			else
				CONS_LOCK+="(assert "+cons_b+")\n\n";
		}
		
		return CONS_LOCK;
	}
	public void addReadWriteConstraints(HashMap<String, Vector<ReadNode>> indexedReadNodes,
			HashMap<String, Vector<WriteNode>> indexedWriteNodes)
	{
		CONS_ASSERT.append(constructReadWriteConstraints(indexedReadNodes,indexedWriteNodes));
	}
	
	public static StringBuilder constructCausalReadWriteConstraintsOptimized(
			Vector<ReadNode> readNodes,
			HashMap<String, Vector<WriteNode>> indexedWriteNodes)
	{
		StringBuilder CONS_CAUSAL_RW = new StringBuilder("");
		
		for(int i=0;i<readNodes.size();i++)
		{
				
			ReadNode rnode = readNodes.get(i);
			
			//get all write nodes on the address
			Vector<WriteNode> writenodes = indexedWriteNodes.get(rnode.getAddr());
			//no write to array field?
			//Yes, it could be: java.io.PrintStream out
			if(writenodes==null||writenodes.size()<2)//
				continue;
			
			WriteNode initNode = writenodes.get(0);

			WriteNode preNode = null;//
			
			//get all write nodes on the address & write the same value
			Vector<WriteNode> writenodes_value_match = new Vector<WriteNode>();
			for(int j=1;j<writenodes.size();j++)//start from j=1 to exclude the initial write
			{
				WriteNode wnode = writenodes.get(j);
				if(wnode.getValue()==rnode.getValue())
				{
					if(wnode.getTid()!=rnode.getTid())
						writenodes_value_match.add(wnode);
					else
					{
						if(preNode ==null
								||(preNode.getGID()<wnode.getGID()&&wnode.getGID()<rnode.getGID()))
							preNode = wnode;
							
					}
				}
			}
			
			if(preNode!=null)
				writenodes_value_match.add(preNode);
			
			//TODO: consider the case when preNode is not null

				String initVar = makeVariable(initNode.getGID());
			
				String var_r = makeVariable(rnode.getGID());
				
				CONS_CAUSAL_RW.append("(assert (> "+var_r+" "+initVar+"))\n");//initial write
						
	
				
				String cons_a="";
				String cons_a_end = "(= "+rnode.getValue()+" "+initNode.getValue()+")\n";

				String cons_b = "";
				String cons_b_end = "false";
				

				
				//make sure all the nodes that x depends on read the same value

				for(int j=0;j<writenodes_value_match.size();j++)
				{
					WriteNode wnode1 = writenodes_value_match.get(j);
					String var_w1 = makeVariable(wnode1.getGID());
					
					
					cons_a= "(and (> "+var_w1+" "+var_r+")\n" + cons_a;
					cons_a_end +=")";
					

					String cons_b_ = "(and (> "+var_r+" "+var_w1+")\n";						

					String cons_c = "";	
					String cons_c_end = "true";
					
					for(int k=0;k<writenodes.size();k++)
					{
						WriteNode wnode2 = writenodes.get(k);
						if(wnode2.getGID()!=wnode1.getGID())
						{
							String var_w2 = makeVariable(wnode2.getGID());
							
							String cons_d = "(and " +
									"(or (> "+var_w2+" "+var_r+")" +
											" (> " +var_w1+" "+var_w2+"))\n";
							
							cons_c= cons_d + cons_c;
							cons_c_end +=")";
						}
					}
					
					cons_c+=cons_c_end;
					
					cons_b_ = cons_b_ + cons_c + ")\n";

					cons_b += "(or "+cons_b_;
					
					cons_b_end +=")";
					
				}
				
				cons_b +=cons_b_end;
				cons_a+=cons_a_end+"\n";
				
				CONS_CAUSAL_RW.append("(assert \n(or \n"+cons_a+" "+cons_b+"))\n\n");
		}
		
		return CONS_CAUSAL_RW;
	}
	public static StringBuilder constructCausalReadWriteConstraints(
			Vector<ReadNode> readNodes,
			HashMap<String, Vector<WriteNode>> indexedWriteNodes)
	{
		StringBuilder CONS_CAUSAL_RW = new StringBuilder("");
		
		for(int i=0;i<readNodes.size();i++)
		{
				
			ReadNode rnode = readNodes.get(i);
			
			//get all write nodes on the address
			Vector<WriteNode> writenodes = indexedWriteNodes.get(rnode.getAddr());
			//no write to array field?
			//Yes, it could be: java.io.PrintStream out
			if(writenodes==null||writenodes.size()<2)//
				continue;
			
			WriteNode initNode = writenodes.get(0);

			//get all write nodes on the address & write the same value
			Vector<WriteNode> writenodes_value_match = new Vector<WriteNode>();
			for(int j=1;j<writenodes.size();j++)//start from j=1 to exclude the initial write
			{
				WriteNode wnode = writenodes.get(j);
				if(wnode.getValue()==rnode.getValue())
					writenodes_value_match.add(wnode);
			}

				String initVar = makeVariable(initNode.getGID());
			
				String var_r = makeVariable(rnode.getGID());
				
				CONS_CAUSAL_RW.append("(assert (> "+var_r+" "+initVar+"))\n");//initial write
						
	
				
				String cons_a="";
				String cons_a_end = "(= "+rnode.getValue()+" "+initNode.getValue()+")\n";

				String cons_b = "";
				String cons_b_end = "false";
				

				
				//make sure all the nodes that x depends on read the same value

				for(int j=0;j<writenodes_value_match.size();j++)
				{
					WriteNode wnode1 = writenodes_value_match.get(j);
					String var_w1 = makeVariable(wnode1.getGID());
					
					
					cons_a= "(and (> "+var_w1+" "+var_r+")\n" + cons_a;
					cons_a_end +=")";
					

					String cons_b_ = "(and (> "+var_r+" "+var_w1+")\n";						

					String cons_c = "";	
					String cons_c_end = "true";
					
					for(int k=0;k<writenodes.size();k++)
					{
						WriteNode wnode2 = writenodes.get(k);
						if(wnode2.getGID()!=wnode1.getGID())
						{
							String var_w2 = makeVariable(wnode2.getGID());
							
							String cons_d = "(and " +
									"(or (> "+var_w2+" "+var_r+")" +
											" (> " +var_w1+" "+var_w2+"))\n";
							
							cons_c= cons_d + cons_c;
							cons_c_end +=")";
						}
					}
					
					cons_c+=cons_c_end;
					
					cons_b_ = cons_b_ + cons_c + ")\n";

					cons_b += "(or "+cons_b_;
					
					cons_b_end +=")";
					
				}
				
				cons_b +=cons_b_end;
				cons_a+=cons_a_end+"\n";
				
				CONS_CAUSAL_RW.append("(assert \n(or \n"+cons_a+" "+cons_b+"))\n\n");
		}
		
		return CONS_CAUSAL_RW;
	}
	
	//does not consider value and causal dependence
	private static String constructReadWriteConstraints(
			HashMap<String, Vector<ReadNode>> indexedReadNodes,
			HashMap<String, Vector<WriteNode>> indexedWriteNodes) {
		
		String CONS_RW = "";
		
		Iterator<Entry<String, Vector<ReadNode>>> 
						entryIt =indexedReadNodes.entrySet().iterator();
		while(entryIt.hasNext())
		{
			Entry<String, Vector<ReadNode>> entry = entryIt.next();
			String addr = entry.getKey();
			
			//get all read nodes on the address
			Vector<ReadNode> readnodes = entry.getValue();
					
			//get all write nodes on the address
			Vector<WriteNode> writenodes = indexedWriteNodes.get(addr);
			
				
			//no write to array field?
			//Yes, it could be: java.io.PrintStream out
			if(writenodes==null||writenodes.size()<2)
				continue;
						
			
			for(int i=0;i<readnodes.size();i++)
			{
				ReadNode rnode = readnodes.get(i);
				String var_r = makeVariable(rnode.getGID());
										
				String cons_a = "";
				
				String cons_a_end = "true";
				
				String cons_b = "";
				String cons_b_end = "false";
				
				//we start from j=1 to exclude the initial write
				for(int j=1;j<writenodes.size();j++)
				{
					WriteNode wnode1 = writenodes.get(j);	
					{
						String var_w1 = makeVariable(wnode1.getGID());

						cons_a= "(and (> "+var_w1+" "+var_r+")\n" + cons_a;
						cons_a_end +=")";
						
						String cons_b_ = "(and (> "+var_r+" "+var_w1+")\n";						
	
						String cons_c = "";	
						String cons_c_end = "true";
						
						for(int k=1;k<writenodes.size();k++)
						{
							if(j!=k)
							{
								WriteNode wnode2 = writenodes.get(k);
								String var_w2 = makeVariable(wnode2.getGID());
	
								String cons_d = "(and " +
										"(or (> "+var_w2+" "+var_r+")" +
												" (> " +var_w1+" "+var_w2+"))\n";
								
								cons_c= cons_d + cons_c;
								cons_c_end +=")";
							}
						}
						
						cons_c+=cons_c_end;
						
						cons_b_ = cons_b_ + cons_c + ")\n";
	
						cons_b += "(or "+cons_b_;
						
						cons_b_end +=")";
					}
				}
				
				cons_b +=cons_b_end;
				
				cons_a+=cons_a_end+"\n";
				

				CONS_RW+="(assert \n(or \n"+cons_a+" "+cons_b+"))\n\n";
			}

		}
		
		return CONS_RW;
	}
	
	public boolean isRace(AbstractNode node1, AbstractNode node2, StringBuilder casualConstraint1, StringBuilder casualConstraint2)
	{
		String var1 = makeVariable(node1.getGID());
		String var2 = makeVariable(node2.getGID());
		
		String QUERY = "(assert (= (+ "+var1+" 1) "+var2+"))\n\n";
		
		id++;
		task = new Z3Run(appname,id);
		StringBuilder msg = new StringBuilder(CONS_DECLARE).append(CONS_ASSERT).append(casualConstraint1).append(casualConstraint2).append(QUERY).append(CONS_GETMODEL);
		task.sendMessage(msg.toString());
		
		return task.sat;
	}

	
	public boolean isRace(AbstractNode node1, AbstractNode node2)
	{
		String var1 = makeVariable(node1.getGID());
		String var2 = makeVariable(node2.getGID());
		
		String QUERY = "(assert (= (+ "+var1+" 1) "+var2+"))\n\n";
				
//				"(assert (or (= (- "+var1+" "+var2+") 1)\n" +
//									"(= (- "+var1+" "+var2+") -1)" +
//											"))\n";
	
				//"(assert (= "+var1+" "+var2+"))\n";//not global order
		id++;
		task = new Z3Run(appname,id);
		StringBuilder msg = new StringBuilder(CONS_DECLARE).append(CONS_ASSERT).append(QUERY).append(CONS_GETMODEL);
		task.sendMessage(msg.toString());
		
		return task.sat;
	}
	public boolean isAtomicityViolation(IMemNode node1, IMemNode node2, IMemNode node3, 
			StringBuilder casualConstraint1, StringBuilder casualConstraint2, StringBuilder casualConstraint3) {
		
		String var1 = makeVariable(node1.getGID());
		String var2 = makeVariable(node2.getGID());
		String var3 = makeVariable(node3.getGID());

		//not global order
		String QUERY = "(assert (and (<= "+var1+" "+var3+")\n" +
									"(<= "+var3+" "+var2+")" +
											"))\n\n";
		
		id++;
		task = new Z3Run(appname,id);
		StringBuilder msg = new StringBuilder(CONS_DECLARE).append(CONS_ASSERT).append(casualConstraint1).append(casualConstraint2).append(casualConstraint3).append(QUERY).append(CONS_GETMODEL);
		task.sendMessage(msg.toString());
		
		return task.sat;
	}
	public boolean isAtomicityViolation(IMemNode node1, IMemNode node2,
			IMemNode node3) {
		
		String var1 = makeVariable(node1.getGID());
		String var2 = makeVariable(node2.getGID());
		String var3 = makeVariable(node3.getGID());

		//not global order
		String QUERY = "(assert (and (<= "+var1+" "+var3+")\n" +
									"(<= "+var3+" "+var2+")" +
											"))\n\n";
		
		id++;
		task = new Z3Run(appname,id);
		StringBuilder msg = new StringBuilder(CONS_DECLARE).append(CONS_ASSERT).append(QUERY).append(CONS_GETMODEL);
		task.sendMessage(msg.toString());
		
		return task.sat;
	}
	
	public boolean isDeadlock(LockPair lp1, LockPair lp2, LockPair lp3,
			LockPair lp4) {
		
		String var1a ="";
		String var1b ="";
		String var2a ="";
		String var2b ="";
		String var3a ="";
		String var3b ="";
		String var4a ="";
		String var4b ="";
		
		if(lp1.lock!=null) var1a = makeVariable(lp1.lock.getGID());
		if(lp1.unlock!=null) var1b = makeVariable(lp1.unlock.getGID());
		
		if(lp2.lock!=null)  var2a = makeVariable(lp2.lock.getGID());
		else return false;//lp2.lock must be here
		
		if(lp2.unlock!=null)  var2b = makeVariable(lp2.unlock.getGID());
		
		if(lp3.lock!=null) var3a = makeVariable(lp3.lock.getGID());
		else return false;//lp3.lock must be here
		if(lp3.unlock!=null)  var3b = makeVariable(lp3.unlock.getGID());
		else return false;//lp3.unlock must be here
		
		if(lp4.lock!=null) var4a = makeVariable(lp4.lock.getGID());
		else return false;//lp4.lock must be here
		if(lp4.unlock!=null)  var4b = makeVariable(lp4.unlock.getGID());
		else return false;//lp4.unlock must be here
		
		String QUERY = "";
		 
		if(lp1.lock!=null)
		QUERY +="(assert (< " +var1a+" "+var2a+"))\n";
		
		QUERY +="(assert (< " +var2a+" "+var3a+"))\n";
				
		if(lp1.unlock!=null)
		QUERY +="(assert (< " +var3b+" "+var1b+"))\n";
		
		if(lp2.unlock!=null)
		QUERY +="(assert (< " +var4b+" "+var2b+"))\n";

		
		id++;
		task = new Z3Run(appname,id);
		StringBuilder msg = new StringBuilder(CONS_DECLARE).append(CONS_ASSERT).append(QUERY).append(CONS_GETMODEL);
		task.sendMessage(msg.toString());
		
		return task.sat;
		
	}
	
	public void detectBugs()
	{
		
	}
	
	public static void testConstructLockConstraints()
	{
		Vector<LockPair> lockPairs = new Vector<LockPair>();
		
		LockPair pair1 = new LockPair(new LockNode(1,1,1,"l",AbstractNode.TYPE.LOCK), 
										new UnlockNode(2,1,2,"l",AbstractNode.TYPE.UNLOCK));
		LockPair pair2 = new LockPair(new LockNode(3,2,3,"l",AbstractNode.TYPE.LOCK), 
				new UnlockNode(4,2,4,"l",AbstractNode.TYPE.UNLOCK));
		LockPair pair3 = new LockPair(new LockNode(5,3,5,"l",AbstractNode.TYPE.LOCK), 
				new UnlockNode(6,3,6,"l",AbstractNode.TYPE.UNLOCK));
		LockPair pair4 = new LockPair(new LockNode(7,4,7,"l",AbstractNode.TYPE.LOCK), 
				new UnlockNode(8,4,8,"l",AbstractNode.TYPE.UNLOCK));

		lockPairs.add(pair1);
		lockPairs.add(pair2);
		lockPairs.add(pair3);
		lockPairs.add(pair4);

		System.out.println(constructLockConstraints(lockPairs));
	}
	
	public static void testConstructReadWriteConstraints()
	{
		HashMap<String, Vector<ReadNode>> indexedReadNodes = new HashMap<String, Vector<ReadNode>>();
		
		HashMap<String, Vector<WriteNode>> indexedWriteNodes = new HashMap<String, Vector<WriteNode>>();
		
		Vector<WriteNode> writeNodes = new Vector<WriteNode>();
		writeNodes.add(new WriteNode(1,1,1,"s","0",AbstractNode.TYPE.WRITE));
		writeNodes.add(new WriteNode(2,2,3,"s","0",AbstractNode.TYPE.WRITE));
		writeNodes.add(new WriteNode(3,3,5,"s","1",AbstractNode.TYPE.WRITE));
		writeNodes.add(new WriteNode(4,4,7,"s","1",AbstractNode.TYPE.WRITE));

		Vector<ReadNode> readNodes = new Vector<ReadNode>();
		readNodes.add(new ReadNode(5,1,2,"s","0",AbstractNode.TYPE.READ));
		readNodes.add(new ReadNode(6,2,4,"s","0",AbstractNode.TYPE.READ));
		readNodes.add(new ReadNode(7,3,6,"s","1",AbstractNode.TYPE.READ));
		readNodes.add(new ReadNode(8,4,8,"s","1",AbstractNode.TYPE.READ));

		indexedWriteNodes.put("s", writeNodes);
		indexedReadNodes.put("s", readNodes);

		System.out.println(constructReadWriteConstraints(indexedReadNodes,indexedWriteNodes));
	}
	
	public static void main(String[] args) throws IOException
	{
		//testConstructLockConstraints();
		testConstructReadWriteConstraints();
	}

	public Vector<String> getSchedule(long endGID, HashMap<Long,Long> nodeGIDTidMap
			,HashMap<Long,String> threadIdNameMap) {
		
		Vector<String> schedule = new Vector<String>();
		for (int i=0;i<task.schedule.size();i++)
		{
			String xi = task.schedule.get(i);
			long gid = Long.valueOf(xi.substring(1));
			long tid = nodeGIDTidMap.get(gid);
			String name = threadIdNameMap.get(tid);
			schedule.add(name);
			if(gid==endGID)
				break;
		}
		
		return schedule;
	}
	


}
