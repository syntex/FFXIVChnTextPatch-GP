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
import java.util.TreeMap;

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
import name.yumao.ffxiv.chn.util.FFXIVString;
import name.yumao.ffxiv.chn.util.HexUtils;
import name.yumao.ffxiv.chn.util.LERandomAccessFile;
import name.yumao.ffxiv.chn.util.LERandomBytes;

public class CSVExport
{
    /**
     * @desc 匯出CSV
     * @param DestinationPath 匯出路徑
     * @param pathToIndexSE 0a0000.win32.index路徑
     * @param slang 資料語言
     * @throws Exception
     */
    public void export(String DestinationPath, String pathToIndexSE, String slang) throws Exception
    {
        File f = new File(DestinationPath + "\\" + slang);
        if (!f.exists())
            f.mkdirs();
        List<String> fileList = initFileList(pathToIndexSE);
        HashMap<Integer, SqPackIndexFolder> indexSE = (new SqPackIndex(pathToIndexSE)).resloveIndex();

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
//            if (!"item.exh".equals(fileName.toLowerCase()))
//                continue;
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

            File csvPath = new File(DestinationPath + "\\" + slang + "\\" + filePatch.substring(3));
            if (!csvPath.exists())
                csvPath.mkdirs();
            File csvFile = new File(csvPath.getAbsoluteFile() + "\\" + fileName.substring(0, fileName.indexOf(".")) + ".csv");
            FileOutputStream outputStream = new FileOutputStream(csvFile);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
            BufferedWriter bwCSV = new BufferedWriter(outputStreamWriter);

            byte[] exhFileSE = extractFile(pathToIndexSE, exhIndexFileSE.getOffset());
            EXHFFile exhSE = new EXHFFile(exhFileSE);
            if ((exhSE.getLangs()).length <= 0)
                continue;
            //先輸出一次title
            for (EXDFPage exdfPage : exhSE.getPages())
            {
                Integer exdFileCRCJA = Integer.valueOf(FFCRC.ComputeCRC(fileName.replace(".EXH", "_" + String.valueOf(exdfPage.pageNum) + "_" + slang + ".EXD").toLowerCase().getBytes()));
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
                TreeMap<Integer, byte[]> jaExdList = new TreeMap<Integer, byte[]>();
                jaExdList.putAll(ja_exd.getEntrys());
                for (Map.Entry<Integer, byte[]> listEntry : jaExdList.entrySet())
                {
                    EXDFEntry exdfEntryJA = new EXDFEntry(listEntry.getValue(), exhSE.getDatasetChunkSize());
                    LERandomBytes chunk = new LERandomBytes(new byte[(exdfEntryJA.getChunk()).length], true, false);
                    chunk.write(exdfEntryJA.getChunk());

                    int index = 0;
                    StringBuilder sbKey = new StringBuilder();
                    StringBuilder sbOffset = new StringBuilder();
                    StringBuilder sbType = new StringBuilder();

                    sbKey.append("key,");
                    sbType.append("#,");
                    sbOffset.append("offset,");
                    for (EXDFDataset exdfDatasetSE : exhSE.getDatasets())
                    {
                        sbKey.append(String.valueOf(index++)).append(",");
                        sbOffset.append(exdfDatasetSE.offset).append(",");
                        sbType.append(exdfDatasetSE.type).append(",");
                    }

                    if (sbKey.length() > 0)
                        sbKey.deleteCharAt(sbKey.length() - 1);
                    if (sbOffset.length() > 0)
                        sbOffset.deleteCharAt(sbOffset.length() - 1);
                    if (sbType.length() > 0)
                        sbType.deleteCharAt(sbType.length() - 1);
                    bwCSV.write(sbKey.toString());
                    bwCSV.newLine();
                    bwCSV.write(sbType.toString());
                    bwCSV.newLine();
                    bwCSV.write(sbOffset.toString());
                    bwCSV.newLine();
                    bwCSV.write(sbType.toString());
                    bwCSV.newLine();
                    break;
                }
                break;
            }
            // 根據標頭檔案輪詢資源檔案
            for (EXDFPage exdfPage : exhSE.getPages())
            {
                Integer exdFileCRCJA = Integer.valueOf(FFCRC.ComputeCRC(fileName.replace(".EXH", "_" + String.valueOf(exdfPage.pageNum) + "_" + slang + ".EXD").toLowerCase().getBytes()));
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
                TreeMap<Integer, byte[]> jaExdList = new TreeMap<Integer, byte[]>();
                jaExdList.putAll(ja_exd.getEntrys());
                for (Map.Entry<Integer, byte[]> listEntry : jaExdList.entrySet())
                {
                    Integer key = listEntry.getKey();
                    EXDFEntry exdfEntryJA = new EXDFEntry(listEntry.getValue(), exhSE.getDatasetChunkSize());
                    LERandomBytes chunk = new LERandomBytes(new byte[(exdfEntryJA.getChunk()).length], true, false);
                    chunk.write(exdfEntryJA.getChunk());
                    StringBuilder sbContent = new StringBuilder();
                    sbContent.append(String.valueOf(key.intValue())).append(",");
                    for (EXDFDataset exdfDatasetSE : exhSE.getDatasets())
                    {
                        if (exdfDatasetSE.type == 0)
                        {
                            byte[] jaBytes = exdfEntryJA.getString(exdfDatasetSE.offset);
                            String jaFFStr = FFXIVString.parseFFXIVString(jaBytes);//儲存CSV的內容直接使用<hex:02100103>這樣的指令碼
                            sbContent.append("\"").append(jaFFStr).append("\",");
                        } else if (exdfDatasetSE.type == 1)
                        {
                            boolean jaBoolean = exdfEntryJA.getBoolean(exdfDatasetSE.offset);
                            sbContent.append(jaBoolean ? "True" : "False").append(",");
                        } else if (exdfDatasetSE.type == 2)
                        {
                            byte jaByte = exdfEntryJA.getByte(exdfDatasetSE.offset);
                            sbContent.append(jaByte).append(",");
                        } else if (exdfDatasetSE.type == 3)
                        {
                            byte jaByte = exdfEntryJA.getByte(exdfDatasetSE.offset);
                            int t = jaByte & 0xFF;
                            sbContent.append(t).append(",");
                        } else if (exdfDatasetSE.type == 4)
                        {
                            short jaShort = exdfEntryJA.getShort(exdfDatasetSE.offset);
                            sbContent.append(jaShort).append(",");
                        } else if (exdfDatasetSE.type == 5) //Quest
                        {
                            short jaShort = exdfEntryJA.getShort(exdfDatasetSE.offset);
                            int t = jaShort & 0xFFFF;
                            sbContent.append(t).append(",");
                        } else if (exdfDatasetSE.type == 7 || exdfDatasetSE.type == 6)
                        {
                            int jaInt = exdfEntryJA.getInt(exdfDatasetSE.offset);
                            sbContent.append(jaInt).append(",");
                        } else if (exdfDatasetSE.type == 9)
                        {
                            float jaFloat = exdfEntryJA.getFloat(exdfDatasetSE.offset);
                            sbContent.append(String.valueOf(jaFloat)).append(",");
                        } else if (exdfDatasetSE.type == 11)//item
                        {
                            int[] q = exdfEntryJA.getQuad(exdfDatasetSE.offset);
                            sbContent.append("\"").append(q[3]).append(", ").append(q[2]).append(", ").append(q[1]).append(", ").append(q[0]).append("\",");
                        } else if (exdfDatasetSE.type >= 25 && exdfDatasetSE.type <= 32)
                        {
                            byte jaByte = exdfEntryJA.getByte(exdfDatasetSE.offset);
                            int filter = (jaByte & 0xFF) & (int) Math.pow(2, exdfDatasetSE.type - 25);
                            sbContent.append(filter == 0 ? "False" : "True").append(",");
                        } else
                        {
                            String s = fileName + ":" + exdfDatasetSE.type;
                            System.err.println(s);
                        }
                    }

                    if (sbContent.length() > 0)
                        sbContent.deleteCharAt(sbContent.length() - 1);
                    bwCSV.write(sbContent.toString());
                    bwCSV.newLine();
                }
            }
            bwCSV.flush();
            bwCSV.close();
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
