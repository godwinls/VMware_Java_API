import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Scanner;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

import com.vmware.vim25.ArrayUpdateOperation;
import com.vmware.vim25.HttpNfcLeaseDeviceUrl;
import com.vmware.vim25.HttpNfcLeaseInfo;
import com.vmware.vim25.HttpNfcLeaseState;
import com.vmware.vim25.OvfCreateDescriptorParams;
import com.vmware.vim25.OvfCreateDescriptorResult;
import com.vmware.vim25.OvfFile;
import com.vmware.vim25.ResourceAllocationInfo;
import com.vmware.vim25.ResourceConfigSpec;
import com.vmware.vim25.SharesInfo;
import com.vmware.vim25.SharesLevel;
import com.vmware.vim25.VAppConfigSpec;
import com.vmware.vim25.VAppEntityConfigInfo;
import com.vmware.vim25.VAppProductInfo;
import com.vmware.vim25.VAppProductSpec;
import com.vmware.vim25.VirtualMachineMovePriority;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.HttpNfcLease;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualApp;
import com.vmware.vim25.mo.VirtualMachine;


public class CreateAndExportVApp {
	public static void main(String[] args) throws Exception
	{
		//args[5]: url, username, password, resource pool name VApp want to be in, VApp name, VMs configure file Path.
		ServiceInstance si = new ServiceInstance(new URL(args[0]), args[1], args[2], true);
        Folder rootFolder = si.getRootFolder();
        
        /*get the VM info from the configure file
         *   The first line of the file will give total number of VMs.
         *   format: 1
         *         //VMname    startOrder      startDelay     stopDelay     startAction     stopAction
                     ubuntu      1               120           0            powerOn        guestShutdown
           Assume we always have the right format configure file.
         */           

		Scanner sc = new Scanner(new FileReader(args[5]));
        int number = sc.nextInt();
        ArrayList<MyVM> VMs= new ArrayList<MyVM>(number);
        for(int i=0; i<number; i++){
        	
        	String name= sc.next();
            int  startOrder = sc.nextInt();
            int  startDelay = sc.nextInt();
            int  stopDelay = sc.nextInt();
            STTA startAction = STTA.valueOf(sc.next());
            STPA stopAction = STPA.valueOf(sc.next()); 
            VMs.add(new MyVM(name,startOrder,startDelay,stopDelay,startAction,stopAction));
        }
        sc.close();
        
        
        ResourcePool rp =(ResourcePool)new InventoryNavigator(rootFolder).searchManagedEntity("ResourcePool",args[3]);
        
        //set the resourceconfig argument in createVApp();
        ResourceConfigSpec resSpec = new ResourceConfigSpec();
             // cpuAllocation
        
        ResourceAllocationInfo cpu = new ResourceAllocationInfo();
        cpu.expandableReservation=true;
        cpu.limit= new Long(-1);
        cpu.reservation = new Long(0);
           SharesInfo shinfo = new SharesInfo();
           shinfo.level=SharesLevel.high;
           shinfo.shares =0;
        cpu.shares=shinfo;
        resSpec.setCpuAllocation(cpu);
              //memeoryAllocation
        ResourceAllocationInfo memory = new ResourceAllocationInfo();
        memory.expandableReservation=true;
        memory.limit = new Long(-1);
        memory.reservation = new Long(0);
        memory.shares=shinfo;
        resSpec.setMemoryAllocation(memory);
        // folder argument in createVApp, use the parent folder of the first vm.
        Folder vmFolder=null;
        VirtualMachine vm =(VirtualMachine)new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine",VMs.get(0).name);
        vmFolder = (Folder)vm.getParent();
        // third argument in createVapp, have to create a empty vapp, then create vms in vapp, then update this config
        VAppConfigSpec configSpec = new VAppConfigSpec();
        
        VirtualApp res= rp.createVApp(args[4], resSpec, configSpec, vmFolder);
        
        // try to migrate the vms given to the vapp. put vms on the first host we see.
        ManagedEntity[] hosts = new InventoryNavigator(rootFolder).searchManagedEntities("HostSystem");
        for(int i=0; i<number; i++){
        	VirtualMachine myvm =(VirtualMachine)new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine",VMs.get(i).name);
        	Task migrate=myvm.migrateVM_Task(res, (HostSystem) hosts[0], VirtualMachineMovePriority.highPriority, VirtualMachinePowerState.poweredOff);
        	if(migrate.waitForTask()==Task.SUCCESS){
    			continue;
    		}else{
    			System.out.println(myvm.getName()+" Migration failed!");
    		}
        }
        
        //update the configspec
        VAppEntityConfigInfo[] vappConfig = new VAppEntityConfigInfo[number];
        for(int i=0; i<number; i++){
        	
        	VirtualMachine myvm =(VirtualMachine)new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine",VMs.get(i).name);
        	VAppEntityConfigInfo vappConfigI = new VAppEntityConfigInfo();
        	vappConfigI.setKey(myvm.getMOR());;
        	vappConfigI.startOrder=VMs.get(i).startOrder;
        	vappConfigI.startDelay=VMs.get(i).startDelay;
        	vappConfigI.startAction = VMs.get(i).startAction.toString();
        	vappConfigI.stopDelay = VMs.get(i).stopDelay;
        	vappConfigI.stopAction = VMs.get(i).stopAction.toString();
        	vappConfig[i]=vappConfigI;
        }
        configSpec.setEntityConfig(vappConfig);

        VAppConfigSpec spec=new VAppConfigSpec();
        VAppProductInfo info = new VAppProductInfo();;
        info.setName("YunzhuoRen_vApp_551");
        info.setVersion("0.283.3.551");
        info.setFullVersion("0.283.3.551");
        info.setVendor("YunzhuoRenvApp551");
        info.setKey(res.getSummary().getProduct().getKey());
		VAppProductSpec singleProduct = new VAppProductSpec();
		singleProduct.setInfo(info);
		ArrayUpdateOperation operation = ArrayUpdateOperation.edit;
		singleProduct.setOperation(operation);
		VAppProductSpec[] product = {singleProduct};
		
		spec.setProduct(product);
		res.updateVAppConfig(spec);
		//The following part is exporting ovf part. code below is from sample code given by @Steve Jin. Minor change is made.
		
		 
		HttpNfcLease hnLease = res.exportVApp();
		 HttpNfcLeaseState hls;
		    for(;;)
		    {
		      hls = hnLease.getState();
		      if(hls == HttpNfcLeaseState.ready)
		      {
		        break;
		      }
		      if(hls == HttpNfcLeaseState.error)
		      {
		        si.getServerConnection().logout();
		        return;
		      }
		    }
		    
		    System.out.println("HttpNfcLeaseState: ready ");
		    HttpNfcLeaseInfo httpNfcLeaseInfo = hnLease.getInfo();
		    httpNfcLeaseInfo.setLeaseTimeout(300*1000*1000);
		    printHttpNfcLeaseInfo(httpNfcLeaseInfo);
		    
		    long diskCapacityInByte = (httpNfcLeaseInfo.getTotalDiskCapacityInKB()) * 1024;

		    leaseProgUpdater = new LeaseProgressUpdater(hnLease, 5000);
		    leaseProgUpdater.start();

		    long alredyWrittenBytes = 0;
		    HttpNfcLeaseDeviceUrl[] deviceUrls = httpNfcLeaseInfo.getDeviceUrl();
		    if (deviceUrls != null) 
		    {
		      OvfFile[] ovfFiles = new OvfFile[deviceUrls.length];
		      System.out.println("Downloading Files:");
		      for (int i = 0; i < deviceUrls.length; i++) 
		      {
		        String deviceId = deviceUrls[i].getKey();
		        String deviceUrlStr = deviceUrls[i].getUrl();
		        String diskFileName = deviceUrlStr.substring(deviceUrlStr.lastIndexOf("/") + 1);
		        String diskUrlStr = deviceUrlStr.replace("*", "130.65.159.10");
		        String diskLocalPath = args[6] + diskFileName;
		        System.out.println("File Name: " + diskFileName);
		        System.out.println("VMDK URL: " + diskUrlStr);
		        String cookie = si.getServerConnection().getVimService().getWsc().getCookie();
		        long lengthOfDiskFile = writeVMDKFile(diskLocalPath, diskUrlStr, cookie, alredyWrittenBytes, diskCapacityInByte);
		        alredyWrittenBytes += lengthOfDiskFile;
		        OvfFile ovfFile = new OvfFile();
		        ovfFile.setPath(diskFileName);
		        ovfFile.setDeviceId(deviceId);
		        ovfFile.setSize(lengthOfDiskFile);
		        ovfFiles[i] = ovfFile;
		      }
		      
		      OvfCreateDescriptorParams ovfDescParams = new OvfCreateDescriptorParams();
		      ovfDescParams.setOvfFiles(ovfFiles);
		      OvfCreateDescriptorResult ovfCreateDescriptorResult = 
		        si.getOvfManager().createDescriptor(res, ovfDescParams);

		      String ovfPath = args[6] + "CMPE283_HW3_vApp_YunzhuoRen_551" + ".ovf";
		      FileWriter out = new FileWriter(ovfPath);
		      out.write(ovfCreateDescriptorResult.getOvfDescriptor());
		      out.close();
		      System.out.println("OVF Desriptor Written to file: " + ovfPath);
		    } 
		    
		    System.out.println("Completed Downloading the files");
		    leaseProgUpdater.interrupt();
		    hnLease.httpNfcLeaseProgress(100);
		    hnLease.httpNfcLeaseComplete();
		    
		si.getServerConnection().logout();
	}
	
	private static void printHttpNfcLeaseInfo(HttpNfcLeaseInfo info) 
	{
		System.out.println("########################  HttpNfcLeaseInfo  ###########################");
		System.out.println("Lease Timeout: " + info.getLeaseTimeout());
		System.out.println("Total Disk capacity: "	+ info.getTotalDiskCapacityInKB());
		HttpNfcLeaseDeviceUrl[] deviceUrlArr = info.getDeviceUrl();
		if (deviceUrlArr != null) 
		{
			int deviceUrlCount = 1;
			for (HttpNfcLeaseDeviceUrl durl : deviceUrlArr) 
			{
				System.out.println("HttpNfcLeaseDeviceUrl : "
						+ deviceUrlCount++);
				System.out.println("	Device URL Import Key: "
						+ durl.getImportKey());
				System.out.println("	Device URL Key: " + durl.getKey());
				System.out.println("	Device URL : " + durl.getUrl());
				System.out.println("	SSL Thumbprint : "	+ durl.getSslThumbprint());
			}
		} 
		else
		{
			System.out.println("No Device URLS Found");
		}
	}

	private static long writeVMDKFile(String localFilePath, String diskUrl, String cookie, 
			long bytesAlreadyWritten, long totalBytes) throws IOException 
	{
		HttpsURLConnection conn = getHTTPConnection(diskUrl, cookie);
		InputStream in = conn.getInputStream();
		OutputStream out = new FileOutputStream(new File(localFilePath));
		byte[] buf = new byte[102400];
		int len = 0;
		long bytesWritten = 0;
		while ((len = in.read(buf)) > 0) 
		{
			out.write(buf, 0, len);
			bytesWritten += len;
			int percent = (int)(((bytesAlreadyWritten + bytesWritten) * 100) / totalBytes);
			leaseProgUpdater.setPercent(percent);
			System.out.println("written: " + bytesWritten);
		}
		in.close();
		out.close();
		return bytesWritten;
	}

	private static HttpsURLConnection getHTTPConnection(String urlStr, String cookieStr) throws IOException 
	{
		HostnameVerifier hv = new HostnameVerifier() 
		{
			public boolean verify(String urlHostName, SSLSession session) 
			{
				return true;
			}
		};
		HttpsURLConnection.setDefaultHostnameVerifier(hv);
		URL url = new URL(urlStr);
		HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

		conn.setDoInput(true);
		conn.setDoOutput(true);
		conn.setAllowUserInteraction(true);
		conn.setRequestProperty("Cookie",	cookieStr);
		conn.connect();
		return conn;
	}
	
	public static LeaseProgressUpdater leaseProgUpdater;

}
