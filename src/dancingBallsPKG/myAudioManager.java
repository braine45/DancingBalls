package dancingBallsPKG;

import java.util.*;
import java.util.concurrent.*;

import ddf.minim.*;
import ddf.minim.analysis.*;
import processing.core.PConstants;

//class to hold all audio and audio analysis - moved from window class

public class myAudioManager {
	public DancingBalls pa;
	public DancingBallWin win;
	
	public static final int numZones = DancingBallWin.numZones;
	//piano visualization object
	public myPianoObj dispPiano;	
	
	//handled sample rates based on songs loaded - put sample rates in keys
	public ConcurrentSkipListMap<Float, Integer> sampleRates;

	//current song index
	public int songIDX = 1;
	
//	//minim audio-related variables
	//holds results from analysis - magnitude key, value is index of note with max level within min/max note bounds
	public ConcurrentSkipListMap<Float, Integer> levelsPerPianoKeyFund;
	//result from analyzing 1st 8 frequencies of harmonic series for each piano note
	public ConcurrentSkipListMap<Float, Integer> levelsPerPKeySingleCalc;
	//results from analysis in the bass, mid and trbl ranges (4 octaves, 3 octaves, 3 octaves
	public ConcurrentSkipListMap<Float, Integer> bassLvlsPerKey,midLvlsPerKey,trblLvlsPerKey;	
	
	public final int fftMinBandwidth = 20, fftBandsPerOctave = 24;
	public final int songBufSize = 1024;
	public float[] blankRes1 = new float[songBufSize];
	public float[] blankRes2 = new float[songBufSize];
	//per zone avg frequencies
	public float[] //blankBands = new float[numZones], 
			bandRes = new float[numZones], bandFreqs = new float[numZones], 
			allBandsRes = new float[numZones], allBandFreqs = new float[numZones];
	public boolean[] beatDetRes = new boolean[numZones], lastBeatDetRes = new boolean[numZones];
	//threads for working on dft analysis
	public List<myDFTNoteMapper> callDFTNoteMapper;
	public List<Future<Boolean>> callDFTMapperFtrs;
	
	public myMP3SongHandler[] pianoClips;
	public String[] pianoNoteFilenames = new String[] {
			"piano-ff-029.wav","piano-ff-030.wav","piano-ff-031.wav","piano-ff-050.wav",
			"piano-ff-051.wav","piano-ff-052.wav","piano-ff-053.wav","piano-ff-054.wav"};
	public myMP3SongHandler[] songs;
	public String[] songFilenames = new String[]{"sati.mp3","PurpleHaze.mp3","UNATCO.mp3","karelia.mp3","choir.mp3"};	
	//current index of fft windowing function, from ui
	public int curWindowIDX = 0;	
	public WindowFunction[] windowList = new WindowFunction[]{FFT.NONE, FFT.BARTLETT, FFT.BARTLETTHANN, FFT.BLACKMAN, FFT.COSINE, FFT.GAUSS, FFT.HAMMING, FFT.HANN, FFT.LANCZOS, FFT.TRIANGULAR};
	
	//beat interaction
	public myBeat[] tapBeats, audioBeats;

	public myAudioManager(DancingBalls _pa,DancingBallWin _win) {
		pa=_pa; win=_win;dispPiano = win.dispPiano;
		initMe();		
	}//myAudioManager
	
	private void initMe() {
		songs = new myMP3SongHandler[songFilenames.length];
		pianoClips = new myMP3SongHandler[pianoNoteFilenames.length];
		//init piano freqs
		//ConcurrentSkipListMap<Float, Integer> allFreqsUsed = 
		dispPiano.initPianoFreqs();
		//initialize tap beat structures
		initTapBeatStructs();
		//load all songs, add sample rate to 
		loadSongsAndFFT();		
		//launch thread to precalculate all trig stuff
		//not needed with multi-threading dft calc - math is fast enough without this
		//pa.th_exec.execute(new myTrigPrecalc(this, allFreqsUsed) );
		//build DFT threads and precalc local cos/sin values
		initDFTAnalysisThrds(10);		
	}//initMe
	
	//initialize array of mybeat to hold results of tapped beat data
	protected void initTapBeatStructs() {
		//beat holding array
		tapBeats = new myBeat[numZones];
		audioBeats = new myBeat[numZones];
		for(int i=0;i<numZones; ++i) {	tapBeats[i] = new myBeat(pa,i);	audioBeats[i] = new myBeat(pa,i);	}		
	}//initTapBeatStructs
	
	//either returns current song or current piano clip
	protected myMP3SongHandler getCurrentClip(int idx) {
		if (win.getPrivFlags(DancingBallWin.usePianoNoteFiles)) {return pianoClips[idx];} else {return songs[idx];}		
	}
	
	protected void setFFTVals() {
		if (win.getPrivFlags(DancingBallWin.fftLogLoaded)){
			for(int i=0;i<songs.length;++i){songs[i].setFFTVals(windowList[curWindowIDX], fftMinBandwidth, fftBandsPerOctave, numZones);}	
			for(int i=0;i<pianoClips.length;++i){	pianoClips[i].setFFTVals(windowList[curWindowIDX], fftMinBandwidth, fftBandsPerOctave, numZones);}				
		}
		
	}//setFFTVals
	
	protected void loadSongsAndFFT() {
		sampleRates = new ConcurrentSkipListMap<Float, Integer>();//hold only sample rates that we have seen
		for(int i=0;i<songs.length;++i){	songs[i] = new myMP3SongHandler(pa.minim, songFilenames[i], win.songList[i], songBufSize);	sampleRates.put(songs[i].playMe.sampleRate(), 1);}		
		for(int i=0;i<pianoClips.length;++i){	pianoClips[i] = new myMP3SongHandler(pa.minim, pianoNoteFilenames[i], win.pianoNoteList[i], songBufSize);	sampleRates.put(pianoClips[i].playMe.sampleRate(), 1);}		
		win.setPrivFlags(DancingBallWin.audioLoaded,true);
		win.setPrivFlags(DancingBallWin.fftLogLoaded, true);
		setFFTVals();
	}//loadSongList() 
	
	//set process audio for each frame
	public boolean processAudioData() {
		boolean updateBall = false;
		myMP3SongHandler song = this.getCurrentClip(songIDX);
		//songs[songIDX].fftFwdOnAudio();
		song.fftFwdOnAudio();
		float[][] res ;
		//artifact from jcuda implementation
//		res = songs[songIDX].fftSpectrumFromAudio(); // real and imaginary components of frequency from sample window
//		res = song.fftSpectrumFromAudio(); // real and imaginary components of frequency from sample window
		//all bands
		//only perform if showing zone bands or ball is receiving audio
		if(win.getPrivFlags(DancingBallWin.stimWithTapBeats) || win.getPrivFlags(DancingBallWin.showZoneBandRes) 
				|| win.getPrivFlags(DancingBallWin.sendAudioToBall) || (win.getPrivFlags(DancingBallWin.showTapBeats) && !win.getPrivFlags(DancingBallWin.useHumanTapBeats) )) {
			res = song.fftFwdNumBandsFromAudio();
			bandRes = res[0];
			bandFreqs = res[1];
			//update ball's knowledge of bandRes
			updateBall = true;
			//ball.setFreqVals(bandRes);
		}
		//only perform if showing all bands eq
		if(win.getPrivFlags(DancingBallWin.showAllBandRes)) {
			//res = songs[songIDX].fftFwdBandsFromAudio();
			res = song.fftFwdBandsFromAudio();
			allBandsRes = res[0];
			allBandFreqs = res[1];		
		}		
		//analyze frequencies of center notes of piano manually
		if(win.getPrivFlags(DancingBallWin.calcSingleFreq)) {			
			setPerRunRes(song.playMe.sampleRate(),song.playMe.mix.toArray());	//send updates to dftAnalyzer		
			try {callDFTMapperFtrs = pa.th_exec.invokeAll(callDFTNoteMapper);for(Future<Boolean> f: callDFTMapperFtrs) { f.get(); }} catch (Exception e) { e.printStackTrace(); }			
		}		
		if(win.getPrivFlags(DancingBallWin.showPianoNotes)) {levelsPerPianoKeyFund = song.fftFwdFreqLevelsInHarmonicBands(dispPiano.pianoMinFreqsHarmonics);}
		//if we're showing beat detected and we're not using human tapped beats
		if((win.getPrivFlags(DancingBallWin.showTapBeats) || win.getPrivFlags(DancingBallWin.stimWithTapBeats)) && ! win.getPrivFlags(DancingBallWin.useHumanTapBeats)) {
			for (int i =0;i<lastBeatDetRes.length;++i) {lastBeatDetRes[i] = beatDetRes[i];}
			beatDetRes = songs[songIDX].beatDetectZones();
			for (int i =0;i<beatDetRes.length;++i) {if(beatDetRes[i]) {	audioBeats[i].addTap(pa.millis()); }		}//not properly measuring beat frequency - need to filter beats			
			//pa.outStr2Scr("zone : 0 beat : " + beatDetRes[0]+" last beat det : " + lastBeatDetRes[0] );
		} else {
			lastBeatDetRes = new boolean[numZones];
			beatDetRes = new boolean[numZones];
		}
		if(song.getPlayPosRatio() > .99) {win.setPrivFlags(DancingBallWin.playMP3Vis, false);}//shut off songs if done
		return updateBall;
	}//processAudioData
	public void changeCurrentSong(int newSongIDX){
		this.getCurrentClip(songIDX).pause();
		//ball.resetVertLocs();
		songIDX = newSongIDX;
		if(win.getPrivFlags(DancingBallWin.playMP3Vis)){this.getCurrentClip(songIDX).play();}
	}//changeCurrentSong
	public void changeCurrentWindowfunc(int newWinFuncIDX) {
		curWindowIDX = newWinFuncIDX;
		if(win.getPrivFlags(DancingBallWin.fftLogLoaded)) {setFFTVals();}
	}//changeCurrentWindowfunc


	public void startAudio(){
		if(!win.getPrivFlags(DancingBallWin.audioLoaded)){loadSongsAndFFT();}//load songs if not loaded already
		//pa.outStr2Scr("Song in buffer : " + songTitles[songIDX] + " size: " +  songs[songIDX].bufferSize() + " Sample rate : "+ songs[songIDX].sampleRate());
		this.getCurrentClip(songIDX).play();
		//send frequencies from fft 
//		if((!privFlags[oceanMadeIDX]) || (null == ball) || (!fftOcean.cudaFlags[fftOcean.doneInit])){return;}
		//ball.setFreqVals(blankRes1, blankRes2,blankBands);			
	}

	public void pauseAudio(){
		if(win.getPrivFlags(DancingBallWin.audioLoaded)){
			stopAllPlaying();
			//this.getCurrentClip(songIDX).pause();
			//songs[songIDX].pause();
		}		
	}
	//stop all clips from playing
	protected void stopAllPlaying() {
		for(int i=0;i<songs.length;++i){	songs[i].pause();	}		
		for(int i=0;i<pianoClips.length;++i){	pianoClips[i].pause();	}				
	}
	//rewind current song
	public void rewindSong() {
		if(win.getPrivFlags(DancingBallWin.playMP3Vis)){
			this.getCurrentClip(songIDX).rewind();
		}		
	}	
	
	public void initDFTAnalysisThrds(int numThreads) {
		//threads 0-3 are bass range
		//4-6 are mid range
		//7-9 are treble range.  perhaps use these to calculate zone behavior?
		callDFTNoteMapper = new ArrayList<myDFTNoteMapper>();
		callDFTMapperFtrs = new ArrayList<Future<Boolean>>();
		int numPerThread = 1+(dispPiano.pianoFreqsHarmonics.length-1)/numThreads;
		int stIdx = 0, endIdx = numPerThread-1;
		for (int i =0;i<numThreads;++i) {		
			callDFTNoteMapper.add(new myDFTNoteMapper(this,stIdx,endIdx));	
			stIdx = endIdx + 1;
			endIdx = stIdx + numPerThread-1;
			if(endIdx > dispPiano.pianoFreqsHarmonics.length - 1) {endIdx = dispPiano.pianoFreqsHarmonics.length-1;}
		}
		pa.outStr2Scr("DFT Threads configured.");
		levelsPerPKeySingleCalc = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
            @Override
            public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
        });
		bassLvlsPerKey = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
            @Override
            public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
        });
		midLvlsPerKey = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
            @Override
            public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
        });
		trblLvlsPerKey = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
            @Override
            public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
        });
	}
	
	//set every time this is run before execution
	private void setPerRunRes(float sampleRate, float[] _buffer) {
		bassLvlsPerKey.clear();
		midLvlsPerKey.clear();
		trblLvlsPerKey.clear();
		
		//threads 0-3 are bass range
		//4-6 are mid range
		//7-9 are treble range.  perhaps use these to calculate zone behavior?
		levelsPerPKeySingleCalc.clear();
		
		for(int i=0;i<4;++i) {callDFTNoteMapper.get(i).setPerRunValues(sampleRate, _buffer,levelsPerPKeySingleCalc, bassLvlsPerKey);}
		for(int i=4;i<7;++i) {callDFTNoteMapper.get(i).setPerRunValues(sampleRate, _buffer, levelsPerPKeySingleCalc,midLvlsPerKey);}
		for(int i=7;i<10;++i) {callDFTNoteMapper.get(i).setPerRunValues(sampleRate, _buffer, levelsPerPKeySingleCalc,trblLvlsPerKey);}
		
		//for (myDFTNoteMapper mapper : callDFTNoteMapper) {mapper.setPerRunValues(sampleRate, _buffer, lclCosTbl, lclSinTbl);}
	}//setPerRunRes
	

	//save the timing of a tapped beat of a certain type - map to zones for now
	//when this is called, key was pressed to signify the location of a beat of type key.
	//this will happen multiple times, and the average of the taps will represent the timing of the beat for type key
	public void saveTapBeat(int key) {
		if (win.getPrivFlags(DancingBallWin.useHumanTapBeats)) {tapBeats[key].addTap(pa.millis());} 
//		else {//if not enabled (not shown) then reset tapBeats struct
//			initTapBeatStructs();
//		}		
	}//saveTapBeat
	//draw bar representing level at a certain band
	private void drawFreqBands(float[] bandRes, float[] bandFreqs, float height, int clr, boolean drawBeats, boolean showFreqs) {
		pa.pushMatrix();pa.pushStyle();
		float transY =-2*height;
		float drawBeatsOffset = drawBeats ? 20 : 0, showFreqsOffset = showFreqs ? (height == 1) ? 180 : 40 : 0;
		pa.translate( 10 + drawBeatsOffset,  win.rectDim[3]+transY);
		float width = (win.rectDim[2]-(40 + drawBeatsOffset + showFreqsOffset)),
				//scale value of how wide to draw the actual data
				wdLogMult = width/6.0f,
				wdMult = width/80.0f;
		if(showFreqs) {
			float txtHt = .4f*height + 5.0f;
			pa.translate(showFreqsOffset,0,0);
			pa.pushMatrix();pa.pushStyle();
			pa.setStroke(win.strkClr);
			pa.textSize(txtHt);
			for (int i=0;i<bandRes.length;++i) {
				pa.pushMatrix();pa.pushStyle();
				//alternate display of freqs on either side of line
				if(height == 1) {pa.translate(-30 *(1 + (i%6)),0,0);} else {pa.translate(-showFreqsOffset,0,0);}// tiny bar do staggered translate
				pa.text(String.format("%5.1f", bandFreqs[i]), -3.0f, txtHt);
				//draw ctr freq name				
				pa.popStyle();pa.popMatrix();		
				pa.translate(0,transY);
			}
			pa.popStyle();pa.popMatrix();		
		}
		for (int i=0;i<bandRes.length;++i) {
			//draw freq bar
			if(height > 1) {//not tiny bar
				pa.noFill();
				pa.setStroke(win.strkClr);
				pa.rect(0,0,width, height);			
				pa.setColorValFill(clr);
			} else {//height is 1==tiny bar
				pa.setColorValFill(clr);
				if(i % 100 == 0) {pa.setColorValFill(pa.gui_White);}
			}
			pa.noStroke();
			//pa.rect(0,0,wdMult * bandRes[i], height);		
			pa.rect(0,0,wdLogMult * (float)Math.log1p(bandRes[i]), height);		
			pa.translate(0,transY);
		}		
		pa.popStyle();pa.popMatrix();		
	}//drawFreqBand
	
	//draw representations of beats on screen
	private void drawBeats(myBeat[] beats, float modAmtMillis, float height) {
		float rad = height/2.0f;
		pa.pushMatrix();pa.pushStyle();
		float transY =-2*height;
		pa.translate( 10 + rad,  win.rectDim[3]+transY + rad);
		for (int i=0;i<beats.length;++i) {			
			beats[i].drawBeat(modAmtMillis, rad);
			pa.translate(0,transY);
		}
		pa.popStyle();pa.popMatrix();	
	}//drawTapBeats()
	
	//display beats detected in music
	private void drawDetectedBeats(boolean[] beatState, boolean[] lastBeatState, float modAmtMillis, float height) {
		float rad = height/2.0f;
		pa.pushMatrix();pa.pushStyle();
		pa.noStroke();
		float transY =-2*height;
		pa.translate(10 + rad,  win.rectDim[3]+transY + rad);
		for (int i=0;i<beatState.length;++i) {		
			if (beatState[i]){	pa.fill(0,255,0,255);} 
			else if (lastBeatState[i]) {pa.fill(255,0,0,255);}
			else {				pa.fill(150,150,150,150);	}//show beat on and determine if it should be turned off			
			pa.sphere(rad);	
			pa.translate(0,transY);
		}
		pa.popStyle();pa.popMatrix();	
	}//drawDetectedBeats	
	public void drawScreenData(float modAmtMillis) {
		pa.hint(PConstants.DISABLE_DEPTH_TEST);
		float bandResHeight = 10.0f;
		boolean showBeats = win.getPrivFlags(DancingBallWin.showTapBeats);
		if(win.getPrivFlags(DancingBallWin.showPianoNotes)) {
			dispPiano.drawMe();	
			if(win.getPrivFlags(DancingBallWin.calcSingleFreq) ) {
				if(!win.getPrivFlags(DancingBallWin.showAllBandRes)) {dispPiano.drawPianoBandRes( levelsPerPKeySingleCalc);}
				if(win.getPrivFlags(DancingBallWin.showEachOctave)) {
					float bandThresh = 5.0f;//TODO set this to something to shut down multi-thread results that are very low
					//threads 0-3 are bass range
					//4-6 are mid range
					//7-9 are treble range.  perhaps use these to calculate zone behavior?
					dispPiano.drawPlayedNote(bassLvlsPerKey, bandThresh, 3, 2);
					dispPiano.drawPlayedNote(midLvlsPerKey, bandThresh, 4, 2);
					dispPiano.drawPlayedNote(trblLvlsPerKey, bandThresh, 5, 2);
				} else {					dispPiano.drawPlayedNote(levelsPerPianoKeyFund, 0, pa.gui_Green, 3);	}
			} else {
				if(!win.getPrivFlags(DancingBallWin.showAllBandRes)) {dispPiano.drawPianoBandRes(levelsPerPianoKeyFund);}
				dispPiano.drawPlayedNote(levelsPerPianoKeyFund, 0 ,pa.gui_Green, 3);
			}
			if(!win.getPrivFlags(DancingBallWin.showAllBandRes)) {dispPiano.drawPianoBandRes(win.getPrivFlags(DancingBallWin.calcSingleFreq) ? levelsPerPKeySingleCalc : levelsPerPianoKeyFund);}
		}		
		if (win.getPrivFlags(DancingBallWin.showAllBandRes)) {//if showing all bands, displace by piano keys' width
			if(win.getPrivFlags(DancingBallWin.showPianoNotes)) {	//move over for piano				
				pa.pushMatrix();pa.pushStyle();
				pa.translate(win.whiteKeyWidth,0,0);				
			}
			drawFreqBands(allBandsRes, allBandFreqs, 1.0f, pa.gui_TransRed, showBeats, win.getPrivFlags(DancingBallWin.showFreqLbls));
		}
		else if(win.getPrivFlags(DancingBallWin.showZoneBandRes)) {drawFreqBands(bandRes, bandFreqs, bandResHeight, pa.gui_Blue, showBeats, win.getPrivFlags(DancingBallWin.showFreqLbls));}
		if(showBeats) {
			if(win.getPrivFlags(DancingBallWin.useHumanTapBeats)) {	drawBeats(tapBeats,modAmtMillis, bandResHeight);}//using human-entered tap beats 
			else {								drawDetectedBeats(beatDetRes, lastBeatDetRes, modAmtMillis, bandResHeight);}//using music-generated beats
		}
		if(win.getPrivFlags(DancingBallWin.showAllBandRes) && win.getPrivFlags(DancingBallWin.showPianoNotes)) {	pa.popStyle();pa.popMatrix();	}		//undo piano translation
		
		pa.hint(PConstants.ENABLE_DEPTH_TEST);		

	}//drawScreenData
	
	

}//class myAudioAnalyzer


//handles an mp3 song, along with transport control
class myMP3SongHandler{	
	public AudioPlayer playMe;	

	//beat detection stuff
	private int sensitivity, insertAt;
	private boolean[] fIsOnset;
	private float[][] feBuffer, fdBuffer;
	private long[] fTimer;

	
	public FFT fft;
	public String fileName, dispName;
	public int songBufSize;		//must be pwr of 2
	//length in millis, minimum frequency in fft, # of zones to map energy to
	public int songLength, minFreqBand, numZones;
	//start frequencies, middle frequencies, and end frequencies for each of numbands bands
	private float[] stFreqs, endFreqs, midFreqs;
	//public static final float[] FreqCtrs = new float[] {};//10 bands : 22Hz to 22KHz
	public int[] stFreqIDX, endFreqIDX;
	
	/**
	 * build song handler
	 * @param _minim ref to minim library object, to load song
	 * @param _fname song name
	 * @param _dname name to display
	 * @param _sbufSize song buffer size
	 */
	public myMP3SongHandler(Minim _minim, String _fname, String _dname, int _sbufSize) {
		fileName = _fname; dispName = _dname;songBufSize = _sbufSize;
		playMe = _minim.loadFile(fileName, songBufSize);
		songLength = playMe.length();
		//playMe.sampleRate()
		fft = new FFT(playMe.bufferSize(), playMe.sampleRate() );
		//beat = new myBeatDetect(playMe.bufferSize(), playMe.sampleRate());//, fft);
		insertAt = 0;
		sensitivity = 10;		
		//System.out.println("Song: " + dispName + " sample rate : " + playMe.sampleRate());
	}	
	
	//set up precomputed frequency arrays for each zone (6)
	private void setupFreqAras() {
		stFreqs = new float[numZones];	endFreqs = new float[numZones];	midFreqs = new float[numZones];
		stFreqIDX = new int[numZones]; endFreqIDX = new int[numZones];
		float maxFreqMult = 9.9f;//max multiplier to minFreqBand to consider
		//multiplier for frequencies to span minFreqBand * ( 1.. maxFreqMult) ==> 2 ^ maxFreqMult/numZones
		float perFreqMult = (float) Math.pow(2.0, maxFreqMult/numZones);
		stFreqs[0] = minFreqBand;
		for(int i=0; i<numZones;++i) {
			endFreqs[i] = stFreqs[i] * perFreqMult;
			stFreqIDX[i] = fft.freqToIndex(stFreqs[i]);
			endFreqIDX[i] = fft.freqToIndex(endFreqs[i]);
			if(i<numZones-1) {//set up next one
				stFreqs[i+1] = endFreqs[i]; 
			}
			midFreqs[i]= .5f *( stFreqs[i] + endFreqs[i]);
		}
	}
	
	//set values required for fft calcs.	
	public void setFFTVals(WindowFunction win, int fftMinBandwidth, int fftBandsPerOctave, int _numZones) {
		numZones = _numZones;//number of zones for dancing ball (6)
		minFreqBand = fftMinBandwidth;//minimum frequency to consider (== minimum bandwidth fft is required to handle)
		setupFreqAras();
		
		//these are used for beat detection in each zone
		fIsOnset = new boolean[numZones];		
		int tmpAraSiz = (int) (playMe.sampleRate() / playMe.bufferSize());
		feBuffer = new float[numZones][tmpAraSiz];
		fdBuffer = new float[numZones][tmpAraSiz];
		fTimer = new long[numZones];
		long start = System.currentTimeMillis();
		for (int i = 0; i < fTimer.length; i++)	{
			fTimer[i] = start;
		}
		//set fft windowing function
		fft.window(win);
		//set fft to not calculate any averages
		fft.noAverages();
	}//setFFTVals
	
//	/**
//	 * calculate the individual level manually using a sample of the signal as f(t)
//	 * @param pianoFreqsHarmonics : array per key of array per harmonic of piano key ideal harmonic series.  idx 0 is fundamental
//	 * @param cosTbl, sinTbl : precalced sin and cos of all frequencies used
//	 * @return map of levels to key indexes, sorted in descending order
//	 */	
//	public ConcurrentSkipListMap<Float, Integer> calcIndivFreqLevel(float[][] pianoFreqsHarmonics,
//																	ConcurrentSkipListMap<Float, Float[]> cosTbl,
//																	ConcurrentSkipListMap<Float, Float[]> sinTbl) {
//		ConcurrentSkipListMap<Float, Integer> res = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
//          @Override
//          public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
//      });
//		//current buffer of song playing
//		float[] buffer = playMe.mix.toArray();
//		float cosSum = 0, sinSum = 0, A;
//		for(int key=0;key<pianoFreqsHarmonics.length;++key) {//for every key being compared
//			cosSum =0;sinSum=0;
//			//for(float harm  : pianoFreqsHarmonics[key]) 
//			{
//			float harm = pianoFreqsHarmonics[key][0];//fundamental only
//				for (int t=0;t<buffer.length; ++t) {		//for every sample
//					cosSum += buffer[t] * cosTbl.get(harm)[t];
//					sinSum += buffer[t] * sinTbl.get(harm)[t];
//				}	           
//			}
//			A = ((cosSum * cosSum) + (sinSum * sinSum)); //A[n] = sqrt (c(f)^2 + s(f)^2)
//			res.put(A, key);				
//		}
//		return res;
//	}//calcIndivFreqLevel
//	
//	/**
//	 * calculate the individual level manually using a sample of the signal as f(t), not using precalced frequencies
//	 * @param pianoFreqsHarmonics : array per key of array per harmonic of piano key ideal harmonic series.  idx 0 is fundamental
//	 * @param cosTbl, sinTbl : precalced sin and cos of all frequencies used
//	 * @return map of levels to key indexes, sorted in descending order
//	 */	
//	public ConcurrentSkipListMap<Float, Integer> calcIndivFreqLevelNoPreCalc(float[][] pianoFreqsHarmonics) {
//		ConcurrentSkipListMap<Float, Integer> res = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
//          @Override
//          public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
//      });
//		//current buffer of song playing
//		float[] buffer = playMe.mix.toArray();		
//		float cosSum = 0, sinSum = 0, A;
//		float twoPiInvSamp = (float) (2.0 * Math.PI / playMe.sampleRate());
//		for(int key=0;key<pianoFreqsHarmonics.length;++key) {//for every key being compared
//			cosSum =0;sinSum=0;
//			//for(float harm  : pianoFreqsHarmonics[key]) 
//			{
//			float harm = pianoFreqsHarmonics[key][0];//fundamental only
//			float tpHarm = harm *  twoPiInvSamp;
//				for (int t=0;t<buffer.length; ++t) {		//for every sample
//					float tpHarmT = t*tpHarm;
//					cosSum += buffer[t] * (float)(Math.cos(tpHarmT));
//					sinSum += buffer[t] * (float)(Math.sin(tpHarmT));
//				}	           
//			}
//			A = ((cosSum * cosSum) + (sinSum * sinSum)); //A[n] = sqrt (c(f)^2 + s(f)^2)
//			res.put(A, key);				
//		}
//		return res;
//	}//calcIndivFreqLevel
	
	//go through entire song, find ideal center frequencies and ranges for each of numZones zone based on energy content
	//split frequency spectrum into 3 areas ("bass", "mid" and "treble"), find numzones/3 ctr frequencies in each of 3 bands
	//# zones should be %3 == 0
	//this should be saved to disk so not repeatedly performed
	private void setZoneFreqs() {
		//first analyze all levels in entire song
		
		//next group frequencies
		
		
	}
	
	
	//call every frame before any fft analysis - steps fft forward over a single batch of samples
	public void fftFwdOnAudio() {fft.forward( playMe.mix ); }
//	//call to get data for fft display - call before any fft analysis
//	public float[][] fftSpectrumFromAudio() {	return new float[][] {fft.getSpectrumReal(), fft.getSpectrumImaginary()};}
	
	//get levels of all bands in spectrum
	public float[][] fftFwdBandsFromAudio() {
		int specSize = fft.specSize();	//should be songBufSize / 2 + 1 : approx 512 bands
		float[] bandRes = new float[specSize], bandFreq = new float[specSize];
		for(int i=0;i<specSize;++i) {	
			bandFreq[i] = fft.indexToFreq(i);
			bandRes[i] = fft.getBand(i);
		}
		return new float[][] {bandRes,bandFreq};
	}//fftFwdBandsFromAudio	

	//return a sorted map keyed by level of piano note idxs present in audio in each band of passed array of freq bands
	//bounds array holds bounds within which to avg levels - idx i is low bound, i+1 is high bound
	public ConcurrentSkipListMap<Float, Integer> fftFwdFreqLevelsInBands(float[] boundsAra){
		ConcurrentSkipListMap<Float, Integer> res = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
          @Override
          public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
      });
		for (int i=0;i<boundsAra.length-1; ++i) {res.put(fft.calcAvg(boundsAra[i], boundsAra[i+1]), i);}		
		return res;
	}//fftFwdFreqLevelsInBands
	
	/**
	 * analyze 1st 8 frequencies of harmonic series for each piano note
	 * @param harmSeries 
	 * @param boundsAra array per key of min frequencies of each key's fundamental and harmonic
	 * @return
	 */
	public ConcurrentSkipListMap<Float, Integer> fftFwdFreqLevelsInHarmonicBands(float[][] keyMinAra){
		//result map is sorted descending
		ConcurrentSkipListMap<Float, Integer> res = new ConcurrentSkipListMap<Float, Integer>(new Comparator<Float>() {
          @Override
          public int compare(Float o1, Float o2) {   return o2.compareTo(o1);}
      });
		float[] keyLoudness = new float[keyMinAra.length-1];		
		//boundsara holds boundaries of min/max freqs for each key
		for (int key=0;key<keyMinAra.length-1; ++key) {keyLoudness[key] = fft.calcAvg(keyMinAra[key][0], keyMinAra[key+1][0]);}		
		
		for (int i=0;i<keyMinAra.length-1; ++i) {
			float freqLvl=0;
			for (int h=0; h < keyMinAra[i].length;++h) {
				freqLvl += fft.calcAvg(keyMinAra[i][h], keyMinAra[i+1][h]);// fft.getFreq(harmSeries[i][h]);
			}	
			freqLvl *= keyLoudness[i];		//weighting by main key level
			res.put(freqLvl, i);
		}		
		return res;
	}//fftFwdFreqLevelsInBands

	
	//beat detect based on minim library implementation
	//detect beats in each predefined zone
	public void beatDetect(float[] avgs) {
		float instant, E, V, C, diff, dAvg, diff2;
		long now = System.currentTimeMillis();
		for (int i = 0; i < numZones; ++i){
			instant = avgs[i];					//level averages at each zone - precalced from fft
			E = calcMean(feBuffer[i]);
			V = calcVar(feBuffer[i], E);
			C = (-0.0025714f * V) + 1.5142857f;
			diff = (float)Math.max(instant - C * E, 0);
			dAvg = specAverage(fdBuffer[i]);
			diff2 = (float)Math.max(diff - dAvg, 0);
			if (now - fTimer[i] < sensitivity){
				fIsOnset[i] = false;
			}else if (diff2 > 0){
				fIsOnset[i] = true;
				fTimer[i] = now;
			}else{
				fIsOnset[i] = false;
			}
			feBuffer[i][insertAt] = instant;
			fdBuffer[i][insertAt] = diff;
		}
		insertAt++;
		if (insertAt == feBuffer[0].length)	{
			insertAt = 0;
		}
	}//

	//returns all spectrum results averaged into numZones bands
	//only use numBands divs of first specSize/2 frequencies
	public float[][] fftFwdNumBandsFromAudio() {
		float[] bandRes = new float[numZones], bandFreq = new float[numZones];
		for(int i=0; i<numZones;++i) {
			bandRes[i] = fft.calcAvg(stFreqs[i], endFreqs[i]);
			bandFreq[i] = midFreqs[i];
		}
		beatDetect(bandRes);
		return new float[][] {bandRes,bandFreq};
	}//fftFwdNumBandsFromAudio
	
	//check this to see if beat has been detected
	public boolean[] beatDetectZones() {
		boolean[] retVal = new boolean[fIsOnset.length];
		for(int i=0;i<fIsOnset.length;++i) {
			retVal[i]=fIsOnset[i];
		}
		return retVal;
	}
	
	private float calcMean(float[] ara){
		float avg = 0;
		for (int i = 0; i < ara.length; ++i){avg += ara[i];}
		avg /= ara.length;
		return avg;
	}

	private float specAverage(float[] arr){
		float avg = 0,num = 0;
		for (int i = 0; i < arr.length; ++i){	if (arr[i] > 0)	{avg += arr[i];++num;}	}
		if (num > 0){avg /= num;}
		return avg;
	}

	private float calcVar(float[] arr, float val){
		float V = 0;
		for (int i = 0; i < arr.length; ++i){V += (float)Math.pow(arr[i] - val, 2);}
		V /= arr.length;
		return V;
	}
	
	//song control
	public void play() {	playMe.play();}
	public void play(int millis) {	playMe.play(millis);}
	public void pause() {	playMe.pause(); if (getPlayPosRatio() >= .99f ) {playMe.rewind();}}
	public void rewind() {  playMe.rewind();}
	//change current playing location
	public void modPlayLoc(float modAmt) {
		int curPos = playMe.position();	
		int dispSize = songLength/20, newPos = (int) (curPos + (dispSize * modAmt));
		if(newPos < 0) { newPos = 0;} else if (newPos > songLength-1){newPos = songLength-1;}
		playMe.cue(newPos);
	}	
	public int getPlayPos() {return playMe.position();}
	public float getPlayPosRatio() {return playMe.position()/(1.0f*songLength);}
}//myMP3SongHandler



//class to hold functionality for tapped beat
class myBeat{
	public DancingBalls pa;
	//# of taps used to determine window to average for beats
	public static int windowSize = 4;
	//time to display beat being on, in milllis
	private static final float beatTtlDispTime = 100.0f;
	//type of beat this represents (i.e. zone)
	public int type;
	//arrays holding beat abs time in millis and beat-to-beat time
	private int[] beats, beatTime;
	//current index in beats and beatTime
	private int beatIDX = 0;
	//running average of beat timing
	public float avgBeatTime, beatDispTime, curBeatTime;
	//this beat structure is ready to consume - only after windowSize elements have been processed
	public boolean ready, beatIsOn;
	
	//
	
	public myBeat(DancingBalls _p, int _type) {
		pa = _p;type=_type;
		//time location of beat taps
		beats = new int[windowSize];
		//time between taps
		beatTime = new int[windowSize];
		avgBeatTime = 0;		
		ready = false;
	}
	//keep a series of 4 beat times, updating each of 4 with subsequent additional info
	public void addTapNew(int tapTime) {//TODO
		
	}
	
	//add timing of new tap, replace oldest entry, recalculate avgBeatTime (avg beat-to-beat timing)
	//tapTime should be in millis
	public void addTap(int tapTime) {
		beats[beatIDX] = tapTime;		//abs time of beat tap
		beatTime[beatIDX] = tapTime - beats[(beatIDX + windowSize - 1)%windowSize];
		beatIDX = (beatIDX + 1)%windowSize;
		if(beatIDX == 0) {
			ready = true;
		}//once windowSize elements have been processed, this structure is ready
		if(ready) {//if ready to, calculate average
			avgBeatTime = 0;
			for(int i=0;i<beatTime.length;++i) {avgBeatTime += beatTime[i];}
			avgBeatTime /= beatTime.length;	
		}
	}//addTap
	//return frequency in hz of beats
	public float getBeatFreq() {
		if(avgBeatTime == 0) {return -1;}
		return 1000.0f/avgBeatTime;
	}
	
	//draw if ready and animTime (time in seconds since last frame)
	public void drawBeat(float animTime, float rad) {
		pa.pushMatrix();				pa.pushStyle();	
		pa.noStroke();
		//NOTE beatTtlDispTime should never be longer than avgBeatTime
		curBeatTime += animTime;		//time between beats
		if(!ready) {
			pa.fill(255,0,0,255);
		} else if (beatIsOn){//show beat on and determine if it should be turned off			
			pa.fill(0,255,0,255);
			beatDispTime += animTime;
			if(beatTtlDispTime < beatDispTime) {//check if displayed long enough
				beatDispTime = 0;
				beatIsOn = false;				
			}
		} else {
			pa.fill(150,150,150,150);
			//check if beat should be on
			if(avgBeatTime <= curBeatTime) {
				curBeatTime = 0;
				beatIsOn = true;				
			}	
		}		
		pa.sphere(rad);
		pa.popStyle();					pa.popMatrix();		
	}//drawBeat	
}//myBeat

