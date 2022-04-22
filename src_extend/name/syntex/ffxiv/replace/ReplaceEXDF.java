package name.syntex.ffxiv.replace;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.regex.Pattern;

import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

import name.syntex.ffxiv.resource.NPCNameMapping;
import name.yumao.ffxiv.chn.builder.BinaryBlockBuilder;
import name.yumao.ffxiv.chn.builder.EXDFBuilder;
import name.yumao.ffxiv.chn.model.EXDFDataset;
import name.yumao.ffxiv.chn.model.EXDFEntry;
import name.yumao.ffxiv.chn.model.EXDFFile;
import name.yumao.ffxiv.chn.model.EXDFPage;
import name.yumao.ffxiv.chn.model.EXHFFile;
import name.yumao.ffxiv.chn.model.SqPackDatFile;
import name.yumao.ffxiv.chn.model.SqPackIndex;
import name.yumao.ffxiv.chn.model.SqPackIndexFile;
import name.yumao.ffxiv.chn.model.SqPackIndexFolder;
import name.yumao.ffxiv.chn.util.ArrayUtil;
import name.yumao.ffxiv.chn.util.FFCRC;
import name.yumao.ffxiv.chn.util.HexUtils;
import name.yumao.ffxiv.chn.util.LERandomAccessFile;
import name.yumao.ffxiv.chn.util.LERandomBytes;
import name.yumao.ffxiv.chn.util.nlpcn.JianFan;
import name.yumao.ffxiv.chn.util.res.Config;

public class ReplaceEXDF
{

    private String pathToIndexSE;
    private String pathToIndexCN;
    private List<String> fileList;

    private HashMap<String, byte[]> exQuestMap;
    private HashMap<String, String> transMap;

    private String slang;
    private String dlang;
    private String flang;
    private boolean csv;

    private NPCNameMapping npcMapping = new NPCNameMapping();

    public ReplaceEXDF(String pathToIndexSE, String pathToIndexCN)
    {
        // 這兩個檔案會是0a0000.win32.index
        this.pathToIndexSE = pathToIndexSE;
        this.pathToIndexCN = pathToIndexCN;
        this.fileList = new ArrayList<>();
        this.exQuestMap = (HashMap) new HashMap<>();
        this.transMap = new HashMap<>();
        this.slang = Config.getProperty("SLanguage");
        this.dlang = Config.getProperty("DLanguage");
        // added
        this.flang = Config.getProperty("FLanguage");
        if (this.flang.equals("CSV"))
        {
            this.csv = true;
        } else
        {
            this.csv = false;
        }
    }

    public void replace() throws Exception
    {
        Pattern pattern = null;
        List<String> skipFiles = null;
        System.out.println("[ReplaceEXDF] Loading Index File...");
        HashMap<Integer, SqPackIndexFolder> indexSE = (new SqPackIndex(this.pathToIndexSE)).resloveIndex();
        System.out.println("[ReplaceEXDF] Initializing File List...");
        initFileList(indexSE);
        System.out.println("[ReplaceEXDF] Loading Index Complete");
        LERandomAccessFile leIndexFile = new LERandomAccessFile(this.pathToIndexSE, "rw");
        LERandomAccessFile leDatFile = new LERandomAccessFile(this.pathToIndexSE.replace("index", "dat0"), "rw");
        long datLength = leDatFile.length();
        leDatFile.seek(datLength);
        // 根據傳入的檔案進行遍歷
        for (String replaceFile : this.fileList)
        {
            if (replaceFile.toUpperCase().endsWith(".EXH"))
            {
                System.out.println("[ReplaceEXDF] Now File : " + replaceFile);
                // 準備好檔案目錄名和檔案名
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
                byte[] exhFileSE = extractFile(this.pathToIndexSE, exhIndexFileSE.getOffset());
                EXHFFile exhSE = new EXHFFile(exhFileSE);
                EXHFFile exhCN = null;
                HashMap<EXDFDataset, EXDFDataset> datasetMap = new HashMap<>();
                boolean cnEXHFileAvailable = false;

                if ((exhSE.getLangs()).length > 0)
                {
                    //讀入CSV資料                    
                    HashMap<Integer, Integer> offsetMap = new HashMap<>();
                    HashMap<Integer, String[]> csvDataMap = new HashMap<>();
                    try
                    {
                        boolean result = readCSVData(replaceFile, offsetMap, csvDataMap);
                        if (!result)
                            continue;
                    } catch (Exception csvFileIndexValueException)
                    {
                        System.out.println("\t\tCSV Exception. " + csvFileIndexValueException.getMessage());
                        continue;
                    }

                    // 根據標頭檔案輪詢資源檔案
                    for (EXDFPage exdfPage : exhSE.getPages())
                    {
                        // 獲取資源檔案的CRC
                        Integer exdFileCRCJA = Integer.valueOf(FFCRC.ComputeCRC(fileName.replace(".EXH", "_" + String.valueOf(exdfPage.pageNum) + "_" + this.slang + ".EXD").toLowerCase().getBytes()));
                        // 進行CRC存在校驗
                        System.out.println("Replace File : " + fileName.substring(0, fileName.indexOf(".")));
                        // 提取對應的文本檔案
                        SqPackIndexFile exdIndexFileJA = (SqPackIndexFile) ((SqPackIndexFolder) indexSE.get(filePatchCRC)).getFiles().get(exdFileCRCJA);
                        byte[] exdFileJA = null;
                        try
                        {
                            exdFileJA = extractFile(this.pathToIndexSE, exdIndexFileJA.getOffset());
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
                        // 填充中文內容，規則可以自行更改
                        for (Map.Entry<Integer, byte[]> listEntry : jaExdList.entrySet())
                        {
                            Integer listEntryIndex = listEntry.getKey();
                            EXDFEntry exdfEntryJA = new EXDFEntry(listEntry.getValue(), exhSE.getDatasetChunkSize());

                            LERandomBytes chunk = new LERandomBytes(new byte[(exdfEntryJA.getChunk()).length], true, false);
                            chunk.write(exdfEntryJA.getChunk());
                            byte[] newFFXIVString = new byte[0];
                            for (EXDFDataset exdfDatasetSE : exhSE.getDatasets())
                            {
                                // 只限文本內容
                                if (exdfDatasetSE.type == 0)
                                {
                                    // added check
                                    // System.out.println("\t\t\texdfDatasetSE.offset: " + exdfDatasetSE.offset);
                                    byte[] jaBytes = exdfEntryJA.getString(exdfDatasetSE.offset);
                                    // jaStr是原文直接轉成String，jafFFStr會將指令碼轉成<hex:02100103>這樣

                                    // 印出內容
                                    // 更新Chunk指針
                                    chunk.seek(exdfDatasetSE.offset);
                                    chunk.writeInt(newFFXIVString.length);
                                    // 更新文本內容
                                    // EXD/warp/WarpInnUldah.EXH -> exd/warp/warpinnuldah_xxxxx_xxxxx

                                    // added CSV mode
                                    // a name like quest or quest/000/ClsHrv001_00003 (replaceFile)
                                    // an offset (exdfDatasetSE.offset)
                                    // System.out.println("\t\toffset: " + String.valueOf(exdfDatasetSE.offset));
                                    Integer offsetInteger = offsetMap.get(Integer.valueOf(exdfDatasetSE.offset));
                                    // System.out.println("\t\t\toffset integer: " + String.valueOf(offsetInteger));
                                    // System.out.println("\t\t\t\tlist entry index: " + String.valueOf(listEntryIndex));
                                    String[] rowStrings = csvDataMap.get(listEntryIndex);
                                    if (rowStrings != null)
                                    {
                                        String readString = npcMapping.mapping(rowStrings[offsetInteger]);
                                        newFFXIVString = transHexString(readString);
                                        if (newFFXIVString == null)
                                            newFFXIVString = ArrayUtil.append(newFFXIVString, jaBytes);
                                    } else
                                    {
                                        newFFXIVString = ArrayUtil.append(newFFXIVString, jaBytes);
                                    }
                                    // newFFXIVString是每一個dataset重置一次
                                    newFFXIVString = ArrayUtil.append(newFFXIVString, new byte[] { 0 });
                                    // System.out.println("\tnewFFXIVString: " + HexUtils.bytesToHexStringWithOutSpace(newFFXIVString));
                                }
                            }
                            // 打包整個Entry %4 Padding
                            byte[] newEntryBody = ArrayUtil.append(chunk.getWork(), newFFXIVString);
                            // System.out.println("\tnewEntryBody: " + HexUtils.bytesToHexStringWithOutSpace(newEntryBody));
                            int paddingSize = 4 - newEntryBody.length % 4;
                            paddingSize = (paddingSize == 0) ? 4 : paddingSize;
                            LERandomBytes entryBody = new LERandomBytes(new byte[newEntryBody.length + paddingSize]);
                            entryBody.write(newEntryBody);
                            // 轉成byte[] 存入Map
                            // System.out.println("\tnetryBody.getWork(): " + HexUtils.bytesToHexStringWithOutSpace(entryBody.getWork()));
                            listEntry.setValue(entryBody.getWork());
                        }
                        // 準備修改好的內容
                        byte[] exdfFile = (new EXDFBuilder(jaExdList)).buildExdf();
                        byte[] exdfBlock = (new BinaryBlockBuilder(exdfFile)).buildBlock();
                        // 填充修改好的內容到新檔案
                        leIndexFile.seek(exdIndexFileJA.getPt() + 8L);
                        leIndexFile.writeInt((int) (datLength / 8L));
                        datLength += exdfBlock.length;
                        leDatFile.write(exdfBlock);
                    }
                }
            }
        }
        leDatFile.close();
        leIndexFile.close();
        System.out.println("Replace Complete");
    }

    private byte[] transHexString(String readString) throws Exception
    {
        byte[] ret = new byte[0];
        String newString = new String();
        boolean isHexString = false;
        if (readString != null)
        {
            for (int i = 0; i < readString.length(); i++)
            {
                char currentChar = readString.charAt(i);
                switch (currentChar)
                {
                case '<':
                {
                    if ((readString.charAt(i + 1) == 'h') && (readString.charAt(i + 2) == 'e') && (readString.charAt(i + 3) == 'x'))
                    {
                        if (isHexString)
                        {
                            throw new Exception("TagInTagException!" + readString);
                        } else
                        {
                            isHexString = true;
                        }
                    }
                    if (newString.length() > 0)
                    {
                        ret = ArrayUtil.append(ret, newString.getBytes("UTF-8"));
                        newString = "";
                    }
                    newString += currentChar;
                    break;
                }
                case '>':
                {
                    newString += currentChar;
                    if (isHexString)
                    {
                        ret = ArrayUtil.append(ret, HexUtils.hexStringToBytes(newString.substring(5, newString.length() - 1)));
                        newString = "";
                        isHexString = false;
                    }
                    break;
                }
                default:
                {
                    newString += currentChar;
                }
                }
            }
            if (newString.length() > 0)
            {
                ret = ArrayUtil.append(ret, newString.getBytes("UTF-8"));
                newString = "";
            }
            return ret;
        }
        return null;
    }

    private boolean readCSVData(String replaceFile, HashMap<Integer, Integer> offsetMap, HashMap<Integer, String[]> csvDataMap) throws UnsupportedEncodingException, FileNotFoundException
    {
        CsvParserSettings csvSettings = new CsvParserSettings();
        csvSettings.setMaxCharsPerColumn(-1);
        csvSettings.setMaxColumns(4096);
        CsvParser csvParser = new CsvParser(csvSettings);
        String csvPath = "resource" + File.separator + "rawexd" + File.separator + replaceFile.substring(4, replaceFile.indexOf(".")) + ".csv";
        if (new File(csvPath).exists())
        {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), "UTF-8"));
            List<String[]> allRows = csvParser.parseAll(br);
            for (int i = 1; i < allRows.get(1).length; i++)
            {
                offsetMap.put(Integer.valueOf((allRows.get(1))[i]), i - 1);
            }
            int rowNumber = allRows.size();
            for (int i = 3; i < rowNumber; i++)
            {
                csvDataMap.put(Integer.valueOf((allRows.get(i))[0]), Arrays.copyOfRange(allRows.get(i), 1, allRows.get(i).length));
            }
        } else
        {
            System.out.println("\t\tCSV file not exists! " + csvPath);
            return false;
        }
        return true;
    }

    private void initFileList(HashMap<Integer, SqPackIndexFolder> indexSE) throws Exception
    {
        Integer filePathCRC = Integer.valueOf(FFCRC.ComputeCRC("exd".toLowerCase().getBytes()));
        Integer rootFileCRC = Integer.valueOf(FFCRC.ComputeCRC("root.exl".toLowerCase().getBytes()));
        SqPackIndexFile rootIndexFileSE = (SqPackIndexFile) ((SqPackIndexFolder) indexSE.get(filePathCRC)).getFiles().get(rootFileCRC);
        byte[] rootFile = extractFile(this.pathToIndexSE, rootIndexFileSE.getOffset());
        BufferedReader rootBufferedReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(rootFile)));
        String fileName;
        while ((fileName = rootBufferedReader.readLine()) != null)
            this.fileList.add("EXD/" + (fileName.contains(",") ? fileName.split(",")[0] : fileName) + ".EXH");
    }

    private byte[] convertString(byte[] chBytes)
    {
        if (this.dlang.equals("CHT"))
        {
            try
            {
                LERandomBytes inBytes = new LERandomBytes(chBytes, true, false);
                LERandomBytes outBytes = new LERandomBytes();
                Vector<byte[]> outBytesVector = (Vector) new Vector<>();
                byte[] newFFXIVStringBytes = new byte[0];
                while (inBytes.hasRemaining())
                {
                    byte b = inBytes.readByte();
                    if (b == 0x02)
                    {
                        outBytesVector.add(outBytes.getWork());
                        outBytes.clear();
                        parsePayload(inBytes, outBytes);
                        outBytesVector.add(outBytes.getWork());
                        outBytes.clear();
                        continue;
                    }
                    outBytes.writeByte(b);
                }
                outBytesVector.add(outBytes.getWork());
                outBytes.clear();
                for (byte[] bytes : outBytesVector)
                {
                    if (bytes.length > 0)
                    {
                        if (bytes[0] == 0x02)
                        {
                            newFFXIVStringBytes = ArrayUtil.append(newFFXIVStringBytes, bytes);
                            continue;
                        }
                        newFFXIVStringBytes = ArrayUtil.append(newFFXIVStringBytes, JianFan.getInstance().j2f(new String(bytes, "UTF-8")).getBytes("UTF-8"));
                    }
                }
                return newFFXIVStringBytes;
            } catch (Exception exception)
            {}
        }
        return chBytes;
    }

    private static void parsePayload(LERandomBytes inBytes, LERandomBytes outBytes)
    {
        int possition = inBytes.position();
        int type = inBytes.readByte() & 0xFF;
        int size = getBodySize(inBytes.readByte() & 0xFF, inBytes);
        byte[] body = new byte[size - 1];
        inBytes.readFully(body);
        inBytes.skip();
        long fullLength = (inBytes.position() - possition + 1);
        byte[] full = new byte[(int) fullLength];
        inBytes.seek(possition - 1);
        inBytes.readFully(full);
        outBytes.write(full);
    }

    private static int getBodySize(int payloadSize, LERandomBytes inBytes)
    {
        if (payloadSize < 0xF0)
            return payloadSize;
        switch (payloadSize)
        {
        case 0xF0:
            return inBytes.readInt8();
        case 0xF1:
        case 0xF2:
            return inBytes.readInt16();
        case 0xFA:
            return inBytes.readInt24();
        case 0xFE:
            return inBytes.readInt32();
        }
        return payloadSize;
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

    private int datasetStringCount(EXDFDataset[] datasets)
    {
        int count = 0;
        for (EXDFDataset dataset : datasets)
        {
            if (dataset.type == 0)
                count++;
        }
        return count;
    }
}
