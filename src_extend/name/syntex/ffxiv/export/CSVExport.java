package name.syntex.ffxiv.export;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import name.yumao.ffxiv.chn.model.EXDFDataset;
import name.yumao.ffxiv.chn.model.EXDFEntry;
import name.yumao.ffxiv.chn.model.EXDFFile;
import name.yumao.ffxiv.chn.model.EXDFPage;
import name.yumao.ffxiv.chn.model.EXHFFile;
import name.yumao.ffxiv.chn.model.SqPackDatFile;
import name.yumao.ffxiv.chn.model.SqPackIndex;
import name.yumao.ffxiv.chn.model.SqPackIndexFile;
import name.yumao.ffxiv.chn.model.SqPackIndexFolder;
import name.yumao.ffxiv.chn.util.FFCRC;
import name.yumao.ffxiv.chn.util.LERandomAccessFile;
import name.yumao.ffxiv.chn.util.LERandomBytes;

public class CSVExport
{
    public void export(String Destination, String pathToIndexSE, String slang) throws Exception
    {
        File f = new File(Destination);
        if (!f.exists())
            f.mkdirs();
        List<String> fileList = initFileList(pathToIndexSE);
        HashMap<Integer, SqPackIndexFolder> indexSE = (new SqPackIndex(pathToIndexSE)).resloveIndex();
        HashMap<Integer, SqPackIndexFolder> indexCN = null;

        LERandomAccessFile leIndexFile = new LERandomAccessFile(pathToIndexSE, "r");
        LERandomAccessFile leDatFile = new LERandomAccessFile(pathToIndexSE.replace("index", "dat0"), "r");
        long datLength = leDatFile.length();
        leDatFile.seek(datLength);
        for (String replaceFile : fileList)
        {
            //EXD/quest/001/ClsRog001_00101.EXH
            if (!replaceFile.toUpperCase().endsWith(".EXH"))
                continue;
            //make csv path and file           

            String filePatch = replaceFile.substring(0, replaceFile.lastIndexOf("/"));
            String fileName = replaceFile.substring(replaceFile.lastIndexOf("/") + 1);

            // 計算檔案目錄CRC
            Integer filePatchCRC = Integer.valueOf(FFCRC.ComputeCRC(filePatch.toLowerCase().getBytes()));
            // 計算 EXH CRC
            Integer exhFileCRC = Integer.valueOf(FFCRC.ComputeCRC(fileName.toLowerCase().getBytes()));
            // 解壓縮並解析EXH
            if (indexSE.get(filePatchCRC) == null)
                continue;
            SqPackIndexFile exhIndexFileSE = (SqPackIndexFile) ((SqPackIndexFolder) indexSE.get(filePatchCRC)).getFiles().get(exhFileCRC);
            if (exhIndexFileSE == null)
                continue;

            File csvPath = new File(Destination + "\\" + filePatch.substring(3));
            if (!csvPath.exists())
                csvPath.mkdirs();
            File csvFile = new File(csvPath.getAbsoluteFile() + "\\" + fileName.substring(0, fileName.indexOf(".")) + ".csv");
            FileOutputStream outputStream = new FileOutputStream(csvFile);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
            BufferedWriter bwCVS = new BufferedWriter(outputStreamWriter);

            byte[] exhFileSE = extractFile(pathToIndexSE, exhIndexFileSE.getOffset());
            EXHFFile exhSE = new EXHFFile(exhFileSE);
            EXHFFile exhCN = null;
            HashMap<EXDFDataset, EXDFDataset> datasetMap = new HashMap<>();
            boolean cnEXHFileAvailable = true;
            if ((exhSE.getLangs()).length <= 0)
                continue;
            HashMap<Integer, Integer> offsetMap = new HashMap<>();
            HashMap<Integer, String[]> csvDataMap = new HashMap<>();
            // 根據標頭檔案輪詢資源檔案
            for (EXDFPage exdfPage : exhSE.getPages())
            {
                Integer exdFileCRCJA = Integer.valueOf(FFCRC.ComputeCRC(fileName.replace(".EXH", "_" + String.valueOf(exdfPage.pageNum) + "_" + slang + ".EXD").toLowerCase().getBytes()));
                // 進行CRC存在校驗
                System.out.println("Replace File : " + fileName.substring(0, fileName.indexOf(".")));
                // 提取對應的文本檔案
                SqPackIndexFile exdIndexFileJA = (SqPackIndexFile) ((SqPackIndexFolder) indexSE.get(filePatchCRC)).getFiles().get(exdFileCRCJA);
                byte[] exdFileJA = null;
                try
                {
                    exdFileJA = extractFile(pathToIndexSE, exdIndexFileJA.getOffset());
                } catch (Exception jaEXDFileException)
                {
                    continue;
                }
                // added 檢查日文檔案是否損毀
                if (exdFileJA == null)
                {
                    System.out.println("      [ERROR] exdFileJA null detected!");
                    System.out.println("      [ERROR] exdIndexFileJA.getOffset(): " + String.valueOf(exdIndexFileJA.getOffset()));
                    continue;
                }
                // 解壓文本檔案並提取內容
                EXDFFile ja_exd = new EXDFFile(exdFileJA);
                HashMap<Integer, byte[]> jaExdList = ja_exd.getEntrys();
                HashMap<Integer, byte[]> chsExdList = null;
                boolean cnEXDFileAvailable = true;
                for (Map.Entry<Integer, byte[]> listEntry : jaExdList.entrySet())
                {
                    Integer listEntryIndex = listEntry.getKey();
                    EXDFEntry exdfEntryJA = new EXDFEntry(listEntry.getValue(), exhSE.getDatasetChunkSize());
                    EXDFEntry exdfEntryCN = null;
                    boolean cnEntryAvailable = true;
                    if (cnEXDFileAvailable)
                    {
                        try
                        {
                            byte[] data = chsExdList.get(listEntryIndex);
                            exdfEntryCN = new EXDFEntry(data, exhCN.getDatasetChunkSize());
                        } catch (Exception cnEntryNullException)
                        {
                            cnEntryAvailable = false;
                        }
                    }
                    LERandomBytes chunk = new LERandomBytes(new byte[(exdfEntryJA.getChunk()).length], true, false);
                    chunk.write(exdfEntryJA.getChunk());
                    byte[] newFFXIVString = new byte[0];
                    int stringCount = 1;
                    //先寫一次offset
                    StringBuilder sbOffset = new StringBuilder();
                    StringBuilder sbType = new StringBuilder();
                    StringBuilder sbContent = new StringBuilder();
                    for (EXDFDataset exdfDatasetSE : exhSE.getDatasets())
                    {
                        //                        if (exdfDatasetSE.type != 0)
                        //                            continue;

                        System.out.println(exdfDatasetSE.offset + ":" + exdfDatasetSE.type);
                        sbOffset.append(exdfDatasetSE.offset).append(",");
                        sbType.append(exdfDatasetSE.type).append(",");
                        if (exdfDatasetSE.type == 0)
                        {
                            byte[] jaBytes = exdfEntryJA.getString(exdfDatasetSE.offset);
                            String jaStr = new String(jaBytes, "UTF-8");
                            String jaFFStr = new String(jaBytes, "UTF-8");
                            sbContent.append(jaFFStr).append(",");
                        }
                        if (exdfDatasetSE.type == 7 || exdfDatasetSE.type == 6)
                        {
                            int jaInt = exdfEntryJA.getInt(exdfDatasetSE.offset);
                            sbContent.append(jaInt).append(",");
                        }
                        if (exdfDatasetSE.type == 5)
                        {
                            short jaShort = exdfEntryJA.getShort(exdfDatasetSE.offset);
                            sbContent.append(jaShort).append(",");
                        }
                        if (exdfDatasetSE.type == 3)
                        {
                            byte jaByte = exdfEntryJA.getByte(exdfDatasetSE.offset);
                            sbContent.append(jaByte).append(",");
                        }

                    }
                    if (sbOffset.length() > 0)
                        sbOffset.deleteCharAt(sbOffset.length() - 1);
                    if (sbType.length() > 0)
                        sbType.deleteCharAt(sbType.length() - 1);
                    bwCVS.write(sbType.toString());
                    bwCVS.newLine();
                    bwCVS.write(sbOffset.toString());
                    bwCVS.newLine();
                    if (sbContent.length() > 0)
                        sbContent.deleteCharAt(sbContent.length() - 1);
                    bwCVS.write(sbContent.toString());
                    bwCVS.newLine();
                }
            }
            bwCVS.flush();
            bwCVS.close();

        }

    }

    private List<String> initFileList(String pathToIndexSE) throws Exception
    {
        List<String> fileList = new ArrayList<>();

        HashMap<Integer, SqPackIndexFolder> indexSE = (new SqPackIndex(pathToIndexSE)).resloveIndex();
        Integer filePathCRC = Integer.valueOf(FFCRC.ComputeCRC("exd".toLowerCase().getBytes()));
        Integer rootFileCRC = Integer.valueOf(FFCRC.ComputeCRC("root.exl".toLowerCase().getBytes()));
        SqPackIndexFile rootIndexFileSE = (SqPackIndexFile) ((SqPackIndexFolder) indexSE.get(filePathCRC)).getFiles().get(rootFileCRC);
        byte[] rootFile = extractFile(pathToIndexSE, rootIndexFileSE.getOffset());
        BufferedReader rootBufferedReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(rootFile)));
        String fileName;
        while ((fileName = rootBufferedReader.readLine()) != null)
            fileList.add("EXD/" + (fileName.contains(",") ? fileName.split(",")[0] : fileName) + ".EXH");
        return fileList;
    }

    private byte[] extractFile(String pathToIndex, long dataOffset) throws IOException, FileNotFoundException
    {
        String pathToOpen = pathToIndex;
        // System.out.println("\tpathToOpen: " + pathToOpen);
        // L代表long數字
        int datNum = (int) ((dataOffset & 0xF) / 2L);
        dataOffset -= dataOffset & 0xF;
        pathToOpen = pathToOpen.replace("index2", "dat" + datNum);
        pathToOpen = pathToOpen.replace("index", "dat" + datNum);
        SqPackDatFile datFile = new SqPackDatFile(pathToOpen);
        // change the boolean to toggle the Debug mode.
        byte[] data = datFile.extractFile(dataOffset * 8L, false);
        datFile.close();
        return data;
    }
}
