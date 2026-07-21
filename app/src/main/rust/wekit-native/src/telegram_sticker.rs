use flate2::read::GzDecoder;
use rasterlottie::{Animation, RenderConfig, Renderer, Rgba8, SupportProfile};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

const OUTPUT_SIZE: u32 = 512;
const TARGET_FPS: f32 = 20.0;
const MAX_FRAMES: usize = 90;

pub fn tgs_to_gif(input_path: &str, output_path: &str) -> Result<(), String> {
    let input = fs::read(input_path).map_err(|error| format!("read TGS: {error}"))?;
    let json = decompress_tgs(&input)?;
    let animation =
        Animation::from_json_str(&json).map_err(|error| format!("parse Lottie: {error}"))?;

    let source_fps = animation.frame_rate.max(1.0);
    let start = animation.in_point.floor();
    let end = animation.out_point.ceil().max(start + 1.0);
    let frame_step = (source_fps / TARGET_FPS.min(source_fps)).round().max(1.0);
    let actual_fps = source_fps / frame_step;
    let mut frames = Vec::new();
    let mut frame = start;
    while frame < end {
        frames.push(frame);
        frame += frame_step;
    }
    if frames.len() > MAX_FRAMES {
        let stride = (frames.len() as f32 / MAX_FRAMES as f32).ceil() as usize;
        frames = frames
            .into_iter()
            .step_by(stride.max(1))
            .take(MAX_FRAMES)
            .collect();
    }
    if frames.is_empty() {
        return Err("TGS contains no renderable frames".to_string());
    }

    let width = animation.width.max(1) as f32;
    let height = animation.height.max(1) as f32;
    let scale = (OUTPUT_SIZE as f32 / width)
        .min(OUTPUT_SIZE as f32 / height)
        .max(0.01);
    let config = RenderConfig::new(Rgba8::TRANSPARENT, scale);
    let profile = SupportProfile {
        allow_effects: true,
        allow_expressions: true,
        allow_unknown_shape_items: true,
        ..SupportProfile::target_corpus()
    };
    let prepared = Renderer::new(profile)
        .prepare(&animation)
        .map_err(|error| format!("prepare Lottie: {error}"))?;
    let first = prepared
        .render_frame(frames[0], config)
        .map_err(|error| format!("render TGS frame: {error}"))?;
    let width = u16::try_from(first.width).map_err(|_| "TGS width is too large".to_string())?;
    let height = u16::try_from(first.height).map_err(|_| "TGS height is too large".to_string())?;
    let delay = ((100.0 / actual_fps).round() as u16).max(1);
    let mut output = Vec::new();
    {
        let mut encoder = gif::Encoder::new(&mut output, width, height, &[])
            .map_err(|error| format!("initialize GIF: {error}"))?;
        encoder
            .set_repeat(gif::Repeat::Infinite)
            .map_err(|error| format!("configure GIF: {error}"))?;
        write_rgba_frame(&mut encoder, width, height, first.pixels, delay)?;
        for frame_number in &frames[1..] {
            let rendered = prepared
                .render_frame(*frame_number, config)
                .map_err(|error| format!("render TGS frame: {error}"))?;
            write_rgba_frame(&mut encoder, width, height, rendered.pixels, delay)?;
        }
    }
    fs::write(output_path, output).map_err(|error| format!("write GIF: {error}"))
}

pub fn png_frames_to_gif(frames_dir: &str, output_path: &str, delay_ms: u32) -> Result<(), String> {
    let mut paths: Vec<PathBuf> = fs::read_dir(frames_dir)
        .map_err(|error| format!("read video frames: {error}"))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.extension()
                .is_some_and(|value| value.eq_ignore_ascii_case("png"))
        })
        .collect();
    paths.sort();
    let first_path = paths
        .first()
        .ok_or_else(|| "video produced no frames".to_string())?;
    let first = decode_png(first_path)?;
    let width =
        u16::try_from(first.width()).map_err(|_| "video frame width is too large".to_string())?;
    let height =
        u16::try_from(first.height()).map_err(|_| "video frame height is too large".to_string())?;
    let delay = ((delay_ms as f32 / 10.0).round() as u16).max(1);
    let mut output = Vec::new();
    {
        let mut encoder = gif::Encoder::new(&mut output, width, height, &[])
            .map_err(|error| format!("initialize GIF: {error}"))?;
        encoder
            .set_repeat(gif::Repeat::Infinite)
            .map_err(|error| format!("configure GIF: {error}"))?;
        write_rgba_frame(&mut encoder, width, height, first.into_raw(), delay)?;
        for path in &paths[1..] {
            let image = decode_png(path)?;
            if image.width() != width as u32 || image.height() != height as u32 {
                return Err("video frames have inconsistent dimensions".to_string());
            }
            write_rgba_frame(&mut encoder, width, height, image.into_raw(), delay)?;
        }
    }
    fs::write(output_path, output).map_err(|error| format!("write GIF: {error}"))
}

fn decompress_tgs(input: &[u8]) -> Result<String, String> {
    if input.starts_with(&[0x1f, 0x8b]) {
        let mut decoder = GzDecoder::new(input);
        let mut output = String::new();
        decoder
            .read_to_string(&mut output)
            .map_err(|error| format!("decompress TGS: {error}"))?;
        Ok(output)
    } else {
        std::str::from_utf8(input)
            .map(str::to_owned)
            .map_err(|_| "TGS is not gzip or UTF-8 Lottie JSON".to_string())
    }
}

fn decode_png(path: &Path) -> Result<image::RgbaImage, String> {
    image::ImageReader::open(path)
        .map_err(|error| format!("open video frame: {error}"))?
        .with_guessed_format()
        .map_err(|error| format!("detect video frame: {error}"))?
        .decode()
        .map_err(|error| format!("decode video frame: {error}"))
        .map(|image| image.into_rgba8())
}

fn write_rgba_frame<W: std::io::Write>(
    encoder: &mut gif::Encoder<W>,
    width: u16,
    height: u16,
    mut pixels: Vec<u8>,
    delay: u16,
) -> Result<(), String> {
    let mut frame = gif::Frame::from_rgba_speed(width, height, &mut pixels, 10);
    frame.delay = delay;
    frame.dispose = gif::DisposalMethod::Background;
    encoder
        .write_frame(&frame)
        .map_err(|error| format!("encode GIF frame: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_tgs_sample_from_environment() {
        let Ok(input) = std::env::var("WEKIT_TGS_TEST_INPUT") else {
            return;
        };
        let output = std::env::temp_dir().join("wekit-telegram-tgs-test.gif");
        tgs_to_gif(&input, output.to_str().expect("UTF-8 output path")).expect("convert TGS");
        let data = fs::read(&output).expect("read GIF output");
        assert!(data.starts_with(b"GIF8"));
        assert!(data.len() > 100);
        let _ = fs::remove_file(output);
    }

    #[test]
    fn encodes_png_frames_from_environment() {
        let Ok(frames) = std::env::var("WEKIT_PNG_FRAMES_TEST_DIR") else {
            return;
        };
        let output = std::env::temp_dir().join("wekit-telegram-webm-test.gif");
        png_frames_to_gif(&frames, output.to_str().expect("UTF-8 output path"), 67)
            .expect("encode PNG frames");
        let data = fs::read(&output).expect("read GIF output");
        assert!(data.starts_with(b"GIF8"));
        assert!(data.len() > 100);
        let _ = fs::remove_file(output);
    }
}
