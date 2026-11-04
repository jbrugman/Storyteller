export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

// Pure so the accepted types and size limits are testable without a browser.
export function validateImageFile(file: File): void {
  if (!file || !['image/png', 'image/jpeg'].includes(file.type)) {
    throw new Error('Only PNG and JPEG images are supported.');
  }
  if (file.size === 0 || file.size > MAX_IMAGE_BYTES) {
    throw new Error('The image must be between 1 byte and 5 MiB.');
  }
}

// Downscale and re-encode an accepted image so a large paste or photo still fits the backend limit.
export async function prepareImage(file: File): Promise<File> {
  validateImageFile(file);
  const maxWidth = 1024;
  const maxHeight = 768;
  const targetBytes = 500 * 1024;
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    const scale = Math.min(1, maxWidth / image.naturalWidth, maxHeight / image.naturalHeight);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Could not prepare the image.');
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    const encode = (quality: number) => new Promise<Blob>((resolve, reject) =>
      canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Could not prepare the image.')), file.type, quality));
    let blob = await encode(0.8);
    if (file.type === 'image/jpeg') {
      for (const quality of [0.7, 0.6]) {
        if (blob.size <= targetBytes) break;
        blob = await encode(quality);
      }
    }
    // Avoid increasing already compact images when no resizing was necessary.
    if (file.type === 'image/png' && scale === 1 && file.size <= blob.size) return file;
    if (blob.size > MAX_IMAGE_BYTES) {
      throw new Error('The prepared image exceeds 5 MiB. Please choose a smaller image.');
    }
    return new File([blob], file.name || 'Pasted image', { type: blob.type });
  } catch (error) {
    throw new Error(error instanceof Error && error.message ? error.message : 'Could not read the image.');
  } finally {
    URL.revokeObjectURL(url);
  }
}
