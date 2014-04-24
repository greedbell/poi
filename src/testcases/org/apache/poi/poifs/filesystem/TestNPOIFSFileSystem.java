/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.poifs.filesystem;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.apache.poi.POIDataSamples;
import org.apache.poi.hpsf.PropertySet;
import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.poifs.common.POIFSConstants;
import org.apache.poi.poifs.property.NPropertyTable;
import org.apache.poi.poifs.property.Property;
import org.apache.poi.poifs.property.RootProperty;
import org.apache.poi.poifs.storage.HeaderBlock;
import org.apache.poi.util.IOUtils;
import org.junit.Test;

/**
 * Tests for the new NIO POIFSFileSystem implementation
 */
public final class TestNPOIFSFileSystem {
   private static final POIDataSamples _inst = POIDataSamples.getPOIFSInstance();

   protected static void assertBATCount(NPOIFSFileSystem fs, int expectedBAT, int expectedXBAT) throws IOException {
       int foundBAT = 0;
       int foundXBAT = 0;
       int sz = (int)(fs.size() / fs.getBigBlockSize());
       for (int i=0; i<sz; i++) {
           if(fs.getNextBlock(i) == POIFSConstants.FAT_SECTOR_BLOCK) {
               foundBAT++;
           }
           if(fs.getNextBlock(i) == POIFSConstants.DIFAT_SECTOR_BLOCK) {
               foundXBAT++;
           }
       }
       assertEquals("Wrong number of BATs", expectedBAT, foundBAT);
       assertEquals("Wrong number of XBATs with " + expectedBAT + " BATs", expectedXBAT, foundXBAT);
   }
   
   protected static HeaderBlock writeOutAndReadHeader(NPOIFSFileSystem fs) throws IOException {
       ByteArrayOutputStream baos = new ByteArrayOutputStream();
       fs.writeFilesystem(baos);
       
       HeaderBlock header = new HeaderBlock(new ByteArrayInputStream(baos.toByteArray()));
       return header;
   }
   
   @Test
   public void basicOpen() throws Exception {
      NPOIFSFileSystem fsA, fsB;
      
      // With a simple 512 block file
      fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize512.zvi"));
      fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize512.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         assertEquals(512, fs.getBigBlockSize());
      }
      
      // Now with a simple 4096 block file
      fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize4096.zvi"));
      fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize4096.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         assertEquals(4096, fs.getBigBlockSize());
      }
   }

   @Test
   public void propertiesAndFatOnRead() throws Exception {
      NPOIFSFileSystem fsA, fsB;
      
      // With a simple 512 block file
      fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize512.zvi"));
      fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize512.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         // Check the FAT was properly processed:
         // Verify we only got one block
         fs.getBATBlockAndIndex(0);
         fs.getBATBlockAndIndex(1);
         try {
            fs.getBATBlockAndIndex(140);
            fail("Should only be one BAT, but a 2nd was found");
         } catch(IndexOutOfBoundsException e) {}
         
         // Verify a few next offsets
         // 97 -> 98 -> END
         assertEquals(98, fs.getNextBlock(97));
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(98));
         
         
         // Check the properties
         NPropertyTable props = fs._get_property_table();
         assertEquals(90, props.getStartBlock());
         assertEquals(7, props.countBlocks());
         
         // Root property tells us about the Mini Stream
         RootProperty root = props.getRoot();
         assertEquals("Root Entry", root.getName());
         assertEquals(11564, root.getSize());
         assertEquals(0, root.getStartBlock());
         
         // Check its children too
         Property prop;
         Iterator<Property> pi = root.getChildren();
         prop = pi.next();
         assertEquals("Thumbnail", prop.getName());
         prop = pi.next();
         assertEquals("\u0005DocumentSummaryInformation", prop.getName());
         prop = pi.next();
         assertEquals("\u0005SummaryInformation", prop.getName());
         prop = pi.next();
         assertEquals("Image", prop.getName());
         prop = pi.next();
         assertEquals("Tags", prop.getName());
         assertEquals(false, pi.hasNext());
         
         
         // Check the SBAT (Small Blocks FAT) was properly processed
         NPOIFSMiniStore ministore = fs.getMiniStore();
         
         // Verify we only got two SBAT blocks
         ministore.getBATBlockAndIndex(0);
         ministore.getBATBlockAndIndex(128);
         try {
            ministore.getBATBlockAndIndex(256);
            fail("Should only be two SBATs, but a 3rd was found");
         } catch(IndexOutOfBoundsException e) {}
         
         // Verify a few offsets: 0->50 is a stream
         for(int i=0; i<50; i++) {
            assertEquals(i+1, ministore.getNextBlock(i));
         }
         assertEquals(POIFSConstants.END_OF_CHAIN, ministore.getNextBlock(50));
      }
      
      // Now with a simple 4096 block file
      fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize4096.zvi"));
      fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize4096.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         // Check the FAT was properly processed
         // Verify we only got one block
         fs.getBATBlockAndIndex(0);
         fs.getBATBlockAndIndex(1);
         try {
            fs.getBATBlockAndIndex(1040);
            fail("Should only be one BAT, but a 2nd was found");
         } catch(IndexOutOfBoundsException e) {}
         
         // Verify a few next offsets
         // 0 -> 1 -> 2 -> END
         assertEquals(1, fs.getNextBlock(0));
         assertEquals(2, fs.getNextBlock(1));
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(2));

         
         // Check the properties
         NPropertyTable props = fs._get_property_table();
         assertEquals(12, props.getStartBlock());
         assertEquals(1, props.countBlocks());
         
         // Root property tells us about the Mini Stream
         RootProperty root = props.getRoot();
         assertEquals("Root Entry", root.getName());
         assertEquals(11564, root.getSize());
         assertEquals(0, root.getStartBlock());
         
         // Check its children too
         Property prop;
         Iterator<Property> pi = root.getChildren();
         prop = pi.next();
         assertEquals("Thumbnail", prop.getName());
         prop = pi.next();
         assertEquals("\u0005DocumentSummaryInformation", prop.getName());
         prop = pi.next();
         assertEquals("\u0005SummaryInformation", prop.getName());
         prop = pi.next();
         assertEquals("Image", prop.getName());
         prop = pi.next();
         assertEquals("Tags", prop.getName());
         assertEquals(false, pi.hasNext());
         
         
         // Check the SBAT (Small Blocks FAT) was properly processed
         NPOIFSMiniStore ministore = fs.getMiniStore();
         
         // Verify we only got one SBAT block
         ministore.getBATBlockAndIndex(0);
         ministore.getBATBlockAndIndex(128);
         ministore.getBATBlockAndIndex(1023);
         try {
            ministore.getBATBlockAndIndex(1024);
            fail("Should only be one SBAT, but a 2nd was found");
         } catch(IndexOutOfBoundsException e) {}
         
         // Verify a few offsets: 0->50 is a stream
         for(int i=0; i<50; i++) {
            assertEquals(i+1, ministore.getNextBlock(i));
         }
         assertEquals(POIFSConstants.END_OF_CHAIN, ministore.getNextBlock(50));
      }
   }
   
   /**
    * Check that for a given block, we can correctly figure
    *  out what the next one is
    */
   @Test
   public void nextBlock() throws Exception {
      NPOIFSFileSystem fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize512.zvi"));
      NPOIFSFileSystem fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize512.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         // 0 -> 21 are simple
         for(int i=0; i<21; i++) {
            assertEquals(i+1, fs.getNextBlock(i));
         }
         // 21 jumps to 89, then ends
         assertEquals(89, fs.getNextBlock(21));
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(89));
         
         // 22 -> 88 simple sequential stream
         for(int i=22; i<88; i++) {
            assertEquals(i+1, fs.getNextBlock(i));
         }
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(88));
         
         // 90 -> 96 is another stream
         for(int i=90; i<96; i++) {
            assertEquals(i+1, fs.getNextBlock(i));
         }
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(96));
         
         // 97+98 is another
         assertEquals(98, fs.getNextBlock(97));
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(98));
         
         // 99 is our FAT block
         assertEquals(POIFSConstants.FAT_SECTOR_BLOCK, fs.getNextBlock(99));
         
         // 100 onwards is free
         for(int i=100; i<fs.getBigBlockSizeDetails().getBATEntriesPerBlock(); i++) {
            assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(i));
         }
      }
      
      // Quick check on 4096 byte blocks too
      fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize4096.zvi"));
      fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize4096.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         // 0 -> 1 -> 2 -> end
         assertEquals(1, fs.getNextBlock(0));
         assertEquals(2, fs.getNextBlock(1));
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(2));
         
         // 4 -> 11 then end
         for(int i=4; i<11; i++) {
            assertEquals(i+1, fs.getNextBlock(i));
         }
         assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(11));
      }
   }

   /**
    * Check we get the right data back for each block
    */
   @Test
   public void getBlock() throws Exception {
      NPOIFSFileSystem fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize512.zvi"));
      NPOIFSFileSystem fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize512.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         ByteBuffer b;
         
         // The 0th block is the first data block
         b = fs.getBlockAt(0);
         assertEquals((byte)0x9e, b.get());
         assertEquals((byte)0x75, b.get());
         assertEquals((byte)0x97, b.get());
         assertEquals((byte)0xf6, b.get());
         
         // And the next block
         b = fs.getBlockAt(1);
         assertEquals((byte)0x86, b.get());
         assertEquals((byte)0x09, b.get());
         assertEquals((byte)0x22, b.get());
         assertEquals((byte)0xfb, b.get());
         
         // Check the final block too
         b = fs.getBlockAt(99);
         assertEquals((byte)0x01, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x02, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
      }
      
      // Quick check on 4096 byte blocks too
      fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize4096.zvi"));
      fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize4096.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB}) {
         ByteBuffer b;
         
         // The 0th block is the first data block
         b = fs.getBlockAt(0);
         assertEquals((byte)0x9e, b.get());
         assertEquals((byte)0x75, b.get());
         assertEquals((byte)0x97, b.get());
         assertEquals((byte)0xf6, b.get());
         
         // And the next block
         b = fs.getBlockAt(1);
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x03, b.get());
         assertEquals((byte)0x00, b.get());

         // The 14th block is the FAT
         b = fs.getBlockAt(14);
         assertEquals((byte)0x01, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x02, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
         assertEquals((byte)0x00, b.get());
      }
   }
   
   /**
    * Ask for free blocks where there are some already
    *  to be had from the FAT
    */
   @Test
   public void getFreeBlockWithSpare() throws Exception {
      NPOIFSFileSystem fs = new NPOIFSFileSystem(_inst.getFile("BlockSize512.zvi"));
      
      // Our first BAT block has spares
      assertEquals(true, fs.getBATBlockAndIndex(0).getBlock().hasFreeSectors());
      
      // First free one is 100
      assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(100));
      assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(101));
      assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(102));
      assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(103));
      
      // Ask, will get 100
      assertEquals(100, fs.getFreeBlock());
      
      // Ask again, will still get 100 as not written to
      assertEquals(100, fs.getFreeBlock());
      
      // Allocate it, then ask again
      fs.setNextBlock(100, POIFSConstants.END_OF_CHAIN);
      assertEquals(101, fs.getFreeBlock());
      
      // All done
      fs.close();
   }

   /**
    * Ask for free blocks where no free ones exist, and so the
    *  file needs to be extended and another BAT/XBAT added
    */
   @Test
   public void getFreeBlockWithNoneSpare() throws Exception {
      NPOIFSFileSystem fs = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize512.zvi"));
      int free;

      // We have one BAT at block 99
      assertEquals(POIFSConstants.FAT_SECTOR_BLOCK, fs.getNextBlock(99));
      
      // We've spare ones from 100 to 128
      for(int i=100; i<128; i++) {
         assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(i));
      }
      
      // Check our BAT knows it's free
      assertEquals(true, fs.getBATBlockAndIndex(0).getBlock().hasFreeSectors());
      
      // Allocate all the spare ones
      for(int i=100; i<128; i++) {
         fs.setNextBlock(i, POIFSConstants.END_OF_CHAIN);
      }
      
      // BAT is now full, but there's only the one
      assertEquals(false, fs.getBATBlockAndIndex(0).getBlock().hasFreeSectors());
      try {
         assertEquals(false, fs.getBATBlockAndIndex(128).getBlock().hasFreeSectors());
         fail("Should only be one BAT");
      } catch(IndexOutOfBoundsException e) {}
      assertBATCount(fs, 1, 0);

      
      // Now ask for a free one, will need to extend the file
      assertEquals(129, fs.getFreeBlock());
      
      assertEquals(false, fs.getBATBlockAndIndex(0).getBlock().hasFreeSectors());
      assertEquals(true, fs.getBATBlockAndIndex(128).getBlock().hasFreeSectors());
      assertEquals(POIFSConstants.FAT_SECTOR_BLOCK, fs.getNextBlock(128));
      assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(129));
      
      // We now have 2 BATs, but no XBATs
      assertBATCount(fs, 2, 0);
      
      
      // Fill up to hold 109 BAT blocks
      for(int i=0; i<109; i++) {
         fs.getFreeBlock();
         int startOffset = i*128;
         while( fs.getBATBlockAndIndex(startOffset).getBlock().hasFreeSectors() ) {
            free = fs.getFreeBlock();
            fs.setNextBlock(free, POIFSConstants.END_OF_CHAIN);
         }
      }
      
      assertEquals(false, fs.getBATBlockAndIndex(109*128-1).getBlock().hasFreeSectors());
      try {
         assertEquals(false, fs.getBATBlockAndIndex(109*128).getBlock().hasFreeSectors());
         fail("Should only be 109 BATs");
      } catch(IndexOutOfBoundsException e) {}
      
      // We now have 109 BATs, but no XBATs
      assertBATCount(fs, 109, 0);
      
      
      // Ask for it to be written out, and check the header
      HeaderBlock header = writeOutAndReadHeader(fs);
      assertEquals(109, header.getBATCount());
      assertEquals(0, header.getXBATCount());
      
      
      // Ask for another, will get our first XBAT
      free = fs.getFreeBlock();
      assertEquals(false, fs.getBATBlockAndIndex(109*128-1).getBlock().hasFreeSectors());
      assertEquals(true, fs.getBATBlockAndIndex(110*128-1).getBlock().hasFreeSectors());
      try {
         assertEquals(false, fs.getBATBlockAndIndex(110*128).getBlock().hasFreeSectors());
         fail("Should only be 110 BATs");
      } catch(IndexOutOfBoundsException e) {}
      assertBATCount(fs, 110, 1);
      
      header = writeOutAndReadHeader(fs);
      assertEquals(110, header.getBATCount());
      assertEquals(1, header.getXBATCount());

      
      // Fill the XBAT, which means filling 127 BATs
      for(int i=109; i<109+127; i++) {
         fs.getFreeBlock();
         int startOffset = i*128;
         while( fs.getBATBlockAndIndex(startOffset).getBlock().hasFreeSectors() ) {
            free = fs.getFreeBlock();
            fs.setNextBlock(free, POIFSConstants.END_OF_CHAIN);
         }
         assertBATCount(fs, i+1, 1);
      }
      
      // Should now have 109+127 = 236 BATs
      assertEquals(false, fs.getBATBlockAndIndex(236*128-1).getBlock().hasFreeSectors());
      try {
         assertEquals(false, fs.getBATBlockAndIndex(236*128).getBlock().hasFreeSectors());
         fail("Should only be 236 BATs");
      } catch(IndexOutOfBoundsException e) {}
      assertBATCount(fs, 236, 1);

      
      // Ask for another, will get our 2nd XBAT
      free = fs.getFreeBlock();
      assertEquals(false, fs.getBATBlockAndIndex(236*128-1).getBlock().hasFreeSectors());
      assertEquals(true, fs.getBATBlockAndIndex(237*128-1).getBlock().hasFreeSectors());
      try {
         assertEquals(false, fs.getBATBlockAndIndex(237*128).getBlock().hasFreeSectors());
         fail("Should only be 237 BATs");
      } catch(IndexOutOfBoundsException e) {}
      
      
      // Check the counts now
      assertBATCount(fs, 237, 2);

      // Check the header
      header = writeOutAndReadHeader(fs);
      
      
      // Now, write it out, and read it back in again fully
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      fs.writeFilesystem(baos);

      // TODO Correct this to work
if(1==2) {
      // Check that it is seen correctly
      fs = new NPOIFSFileSystem(new ByteArrayInputStream(baos.toByteArray()));
      assertBATCount(fs, 237, 2);
      // TODO Do some more checks
}
      
      // All done
      fs.close();
   }
   
   /**
    * Test that we can correctly get the list of directory
    *  entries, and the details on the files in them
    */
   @Test
   public void listEntries() throws Exception {
      NPOIFSFileSystem fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize512.zvi"));
      NPOIFSFileSystem fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize512.zvi"));
      NPOIFSFileSystem fsC = new NPOIFSFileSystem(_inst.getFile("BlockSize4096.zvi"));
      NPOIFSFileSystem fsD = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize4096.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB,fsC,fsD}) {
         DirectoryEntry root = fs.getRoot();
         assertEquals(5, root.getEntryCount());
         
         // Check by the names
         Entry thumbnail = root.getEntry("Thumbnail");
         Entry dsi = root.getEntry("\u0005DocumentSummaryInformation");
         Entry si = root.getEntry("\u0005SummaryInformation");
         Entry image = root.getEntry("Image");
         Entry tags = root.getEntry("Tags");
         
         assertEquals(false, thumbnail.isDirectoryEntry());
         assertEquals(false, dsi.isDirectoryEntry());
         assertEquals(false, si.isDirectoryEntry());
         assertEquals(true, image.isDirectoryEntry());
         assertEquals(false, tags.isDirectoryEntry());
         
         // Check via the iterator
         Iterator<Entry> it = root.getEntries();
         assertEquals(thumbnail.getName(), it.next().getName());
         assertEquals(dsi.getName(), it.next().getName());
         assertEquals(si.getName(), it.next().getName());
         assertEquals(image.getName(), it.next().getName());
         assertEquals(tags.getName(), it.next().getName());
         
         // Look inside another
         DirectoryEntry imageD = (DirectoryEntry)image;
         assertEquals(7, imageD.getEntryCount());
      }
   }
   
   /**
    * Tests that we can get the correct contents for
    *  a document in the filesystem 
    */
   @Test
   public void getDocumentEntry() throws Exception {
      NPOIFSFileSystem fsA = new NPOIFSFileSystem(_inst.getFile("BlockSize512.zvi"));
      NPOIFSFileSystem fsB = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize512.zvi"));
      NPOIFSFileSystem fsC = new NPOIFSFileSystem(_inst.getFile("BlockSize4096.zvi"));
      NPOIFSFileSystem fsD = new NPOIFSFileSystem(_inst.openResourceAsStream("BlockSize4096.zvi"));
      for(NPOIFSFileSystem fs : new NPOIFSFileSystem[] {fsA,fsB,fsC,fsD}) {
         DirectoryEntry root = fs.getRoot();
         Entry si = root.getEntry("\u0005SummaryInformation");
         
         assertEquals(true, si.isDocumentEntry());
         DocumentNode doc = (DocumentNode)si;
         
         // Check we can read it
         NDocumentInputStream inp = new NDocumentInputStream(doc);
         byte[] contents = new byte[doc.getSize()];
         assertEquals(doc.getSize(), inp.read(contents));
         
         // Now try to build the property set
         inp = new NDocumentInputStream(doc);
         PropertySet ps = PropertySetFactory.create(inp);
         SummaryInformation inf = (SummaryInformation)ps;
         
         // Check some bits in it
         assertEquals(null, inf.getApplicationName());
         assertEquals(null, inf.getAuthor());
         assertEquals(null, inf.getSubject());
         
         // Finish
         inp.close();
      }
   }
   
   /**
    * Read a file, write it and read it again.
    * Then, alter+add some streams, write and read
    */
   @Test
   public void readWriteRead() throws Exception {
      // TODO
      // TODO
   }
   
   /**
    * Create a new file, write it and read it again
    * Then, add some streams, write and read
    */
   @Test
   public void createWriteRead() throws Exception {
      NPOIFSFileSystem fs = new NPOIFSFileSystem();
      
      // Initially has a BAT but not SBAT
      assertEquals(POIFSConstants.FAT_SECTOR_BLOCK, fs.getNextBlock(0));
      assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(1));
      assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(2));
      
      // Check that the SBAT is empty
      assertEquals(POIFSConstants.END_OF_CHAIN, fs.getRoot().getProperty().getStartBlock());

      // Write and read it
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      fs.writeFilesystem(baos);
      fs = new NPOIFSFileSystem(new ByteArrayInputStream(baos.toByteArray()));
      
      // Property table entries have been added to the blocks 
      assertEquals(POIFSConstants.FAT_SECTOR_BLOCK, fs.getNextBlock(0));
      assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(1));
      assertEquals(POIFSConstants.END_OF_CHAIN, fs.getNextBlock(2));
      assertEquals(POIFSConstants.UNUSED_BLOCK, fs.getNextBlock(3));
      assertEquals(POIFSConstants.END_OF_CHAIN, fs.getRoot().getProperty().getStartBlock());

      // Now add a normal stream and a mini stream
      // TODO
      
      // TODO The rest of the test
   }

   @Test
   public void writPOIFSWriterListener() throws Exception {
       File testFile = POIDataSamples.getSpreadSheetInstance().getFile("Simple.xls");
       NPOIFSFileSystem src = new NPOIFSFileSystem(testFile);
       byte wbDataExp[] = IOUtils.toByteArray(src.createDocumentInputStream("Workbook"));
       
       NPOIFSFileSystem nfs = new NPOIFSFileSystem();
       copy(src.getRoot(), nfs.getRoot());
       src.close();

       ByteArrayOutputStream bos = new ByteArrayOutputStream();
       nfs.writeFilesystem(bos);
       nfs.close();
       
       POIFSFileSystem pfs = new POIFSFileSystem(new ByteArrayInputStream(bos.toByteArray()));
       byte wbDataAct[] = IOUtils.toByteArray(pfs.createDocumentInputStream("Workbook"));
       
       assertThat(wbDataExp, equalTo(wbDataAct));
   }

   private static void copy(final DirectoryNode src, final DirectoryNode dest) throws IOException {
       Iterator<Entry> srcIter = src.getEntries();
       while(srcIter.hasNext()) {
           Entry entry = srcIter.next();
           if (entry.isDirectoryEntry()) {
               DirectoryNode srcDir = (DirectoryNode)entry;
               DirectoryNode destDir = (DirectoryNode)dest.createDirectory(srcDir.getName());
               destDir.setStorageClsid(src.getStorageClsid());
               copy(srcDir, destDir);
           } else {
               final DocumentNode srcDoc = (DocumentNode)entry;
               // dest.createDocument(srcDoc.getName(), src.createDocumentInputStream(srcDoc));
               dest.createDocument(srcDoc.getName(), srcDoc.getSize(), new POIFSWriterListener() {
                   public void processPOIFSWriterEvent(POIFSWriterEvent event) {
                       try {
                           DocumentInputStream dis = src.createDocumentInputStream(srcDoc);
                           IOUtils.copy(dis, event.getStream());
                       } catch (IOException e) {
                           throw new RuntimeException(e);
                       }
                   }
               });
           }
       }
       
   }
   
   
   // TODO Directory/Document write tests
}
