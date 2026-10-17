import { Config } from '@remotion/cli/config';

// PNG frames: JPEG ones carry full-range colour into the video (yuvj420p), which some players and
// browsers show washed out. PNG is slower to capture, but the video comes out as standard yuv420p.
Config.setVideoImageFormat('png');
Config.setOverwriteOutput(true);
