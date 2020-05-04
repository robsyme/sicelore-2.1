package org.ipmc.sicelore.programs;

/**
 *
 * @author kevin lebrigand
 * 
 */ 
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import java.io.*;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.ipmc.sicelore.utils.*;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import picard.cmdline.CommandLineProgram;

@CommandLineProgramProperties(summary = "Collapse molecules Bam file to model Bam File", oneLineSummary = "Collapse molecules Bam file to model Bam File", programGroup = org.ipmc.sicelore.cmdline.SiCeLoRe.class)
@DocumentedFeature
public class CollapseModel extends CommandLineProgram
{ 
    @Argument(shortName = "I", doc = "The input SAM or BAM file")
    public File INPUT;
    @Argument(shortName = "REFFLAT", doc = "The refFlat gene model file")
    public File REFFLAT;
    @Argument(shortName = "CSV", doc = "The cell barcodes .csv file")
    public File CSV;
    @Argument(shortName = "DELTA", doc = "Allowed base number difference between start/end of exons and read block position (default=2)")
    public int DELTA = 2;
    @Argument(shortName = "MINEVIDENCE", doc = "Minimum evidence for Novel isoforms to be kept (default=2 molecules)")
    public int MINEVIDENCE = 2;
    @Argument(shortName = "RNMIN", doc = "Minimum number of reads to consider the UMI for Novel isoforms identification (default=3 reads)")
    public int RNMIN = 3;
    @Argument(shortName = "OUTDIR", doc = "The output directory")
    public File OUTDIR;
    @Argument(shortName = "PREFIX", doc = "Prefix for output file names (default=CollapseModel)")
    public String PREFIX = "CollapseModel";
    @Argument(shortName = "CELLTAG", doc = "Cell tag (default=BC)", optional=true)
    public String CELLTAG = "BC";
    @Argument(shortName = "UMITAG", doc = "UMI tag (default=U8)", optional=true)
    public String UMITAG = "U8";
    @Argument(shortName = "GENETAG", doc = "Gene name tag (default=IG)", optional=true)
    public String GENETAG = "IG";
    @Argument(shortName = "ISOFORMTAG", doc = "Isoform tag (default=IT)", optional=true)
    public String ISOFORMTAG = "IT";
    @Argument(shortName = "RNTAG", doc = "Read number tag (default=RN)", optional=true)
    public String RNTAG = "RN";
    @Argument(shortName = "TMPDIR", doc = "TMPDIR")
    public String TMPDIR = "/share/data/scratch/sicelore/";
    @Argument(shortName = "T", doc = "The number of threads (default 20)")
    public int nThreads = 20;
    
    @Argument(shortName = "SHORT", doc = "The short read SAM or BAM file fot junction validation")
    public File SHORT;
    @Argument(shortName = "CAGE", doc = "CAGE peaks file (.bed)")
    public File CAGE;
    
    // ftp://ftp.ebi.ac.uk/pub/databases/gencode/Gencode_mouse/release_M24/gencode.vM24.polyAs.gtf.gz
    // more +6 gencode.vM24.polyAs.gtf  | grep polyA_signal | awk '{ print $1 "\t" $4 "\t" $5 "\t" $1 ":" $4 ".." $5 "," $7 "\t1\t" $7 "\t" $4 "\t" $4+1 }' > gencode.vM24.polyAs.bed
    // sed -i -e "s/chr//g" gencode.vM24.polyAs.bed
    @Argument(shortName = "POLYA", doc = "POLYA sites file (.bed)")
    public File POLYA;

    @Argument(shortName = "cageCo", doc = "CAGE validation cutoff (default=50 bases)")
    public int cageCo = 50;
    @Argument(shortName = "polyaCo", doc = "PolyA validation cutoff (default=50 bases)")
    public int polyaCo = 50;
    @Argument(shortName = "juncCo", doc = "Junction validation cutoff (default=1 read)")
    public int juncCo = 1;
    
    public HashSet<String> allcmp = new HashSet<String>();
    BEDParser cage;
    BEDParser polyA;
    
    public CellList cellList;
    private ProgressLogger pl;
    private final Log log;
    
    private boolean doConsCall = false;
    
    UCSCRefFlatParser refmodel;
    UCSCRefFlatParser mymodel;

    public CollapseModel()
    {
        log = Log.getInstance(CollapseModel.class);
        pl = new ProgressLogger(log);
    }

    protected int doWork()
    {
        IOUtil.assertFileIsReadable(REFFLAT);
        IOUtil.assertFileIsReadable(INPUT);
        IOUtil.assertFileIsReadable(CSV);
        
        String POAPATH = this.findExecutableOnPath("poa");
        String RACONPATH = this.findExecutableOnPath("racon");
        String MINIMAP2PATH = this.findExecutableOnPath("minimap2");
        
        if(POAPATH == null)
            log.info(new Object[]{"Unable to find poa, please add it to your PATH"});
        else if(RACONPATH == null)
            log.info(new Object[]{"Unable to find racon, please add it to your PATH"});
        else if(MINIMAP2PATH == null)
            log.info(new Object[]{"Unable to find minimap2, please add it to your PATH"});
        else{
            Consensus c = new Consensus();
            c.setStaticParams(TMPDIR,POAPATH,RACONPATH,MINIMAP2PATH);
            this.doConsCall = true; // change to true if needed
        }
        
        process();
        return 0;
    }
    
    public static String findExecutableOnPath(String name)
    {
        for (String dirname : System.getenv("PATH").split(File.pathSeparator)) {
            File file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }
    
    protected void process()
    {
        this.cellList = new CellList(CSV);
        log.info(new Object[]{"\tCells detected\t\t[" + this.cellList.size() + "]"});
       
        // initialize models
        refmodel = new UCSCRefFlatParser(REFFLAT);
        mymodel = new UCSCRefFlatParser(DELTA, MINEVIDENCE, RNMIN, refmodel);
        
        mymodel.loader(INPUT,CELLTAG,UMITAG,GENETAG,ISOFORMTAG,RNTAG);
        mymodel.collapser();
        
        mymodel.initialize();
        //mymodel.statistics();
        
        mymodel.filter();
        mymodel.classifier();
        
        if(CAGE.exists() && POLYA.exists() && SHORT.exists()){
            log.info(new Object[]{"\tPerform validation using provided CAGE bed, POLYA bed and SHORT read bam files"});
            cage = new BEDParser(CAGE);
            polyA = new BEDParser(POLYA);
            mymodel.validator(cage, polyA, SHORT, cageCo, polyaCo, juncCo);
        }
        else
            log.info(new Object[]{"\tWon't perform validation (please provide CAGE bed, POLYA bed and SHORT read bam files"});
        
        mymodel.statistics();
        
        File TXT = new File(OUTDIR.getAbsolutePath() + "/" + PREFIX + ".d" + DELTA + ".rn" + RNMIN + ".e" + MINEVIDENCE + ".txt");
        File FLAT = new File(OUTDIR.getAbsolutePath() + "/" + PREFIX + ".d" + DELTA + ".rn" + RNMIN + ".e" + MINEVIDENCE + ".refflat.txt");
        File FLATVALID = new File(OUTDIR.getAbsolutePath() + "/" + PREFIX + ".d" + DELTA + ".rn" + RNMIN + ".e" + MINEVIDENCE + ".final.refflat.txt");
        File GFF = new File(OUTDIR.getAbsolutePath() + "/" + PREFIX + ".d" + DELTA + ".rn" + RNMIN + ".e" + MINEVIDENCE + ".gff");
        File GFFVALID = new File(OUTDIR.getAbsolutePath() + "/" + PREFIX + ".d" + DELTA + ".rn" + RNMIN + ".e" + MINEVIDENCE + ".final.gff");
        mymodel.exportFiles(TXT,FLAT,FLATVALID,GFF,GFFVALID);
        
        // this is where we need to phase haplotype instead of aclling consensus/
        // allelic determination and output an allele specific counting matrix
        // define nachor for phasing: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5025529/
        // on the todo list !!
        if(this.doConsCall){
            log.info(new Object[]{"\tExporting all isoforms consensus sequence to fasta..."});
            File FAS = new File(OUTDIR.getAbsolutePath() + "/" + PREFIX + ".d" + DELTA + ".rn" + RNMIN + ".e" + MINEVIDENCE + ".fas");
            mymodel.callConsensus(FAS, nThreads);
        }
    }
                    
    public static void main(String[] args) {
        System.exit(new CollapseModel().instanceMain(args));
    }
}