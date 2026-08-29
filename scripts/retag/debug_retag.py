import sys, json
sys.path.insert(0, r'D:\项目\WeChatBot\scripts\retag')
from retag_outfits_fashion_florence import load_model, fetch_image, PROMPT

import torch

model, processor = load_model('cuda')

d = json.load(open(r'D:\项目\WeChatBot\data\image_urls.json', encoding='utf-8'))
entry = None
for k, v in d.items():
    if k == '002':
        entry = v
        break
assert entry is not None, "002 not found"
ov = entry['overview']
url = ov if isinstance(ov, str) else ov.get('url', '')
print("URL:", url, flush=True)

img = fetch_image(url)
print("image loaded:", type(img), getattr(img, 'size', None), flush=True)

inputs = processor(text=PROMPT, images=img, return_tensors="pt").to('cuda', torch.float16)
print("input_ids shape:", inputs['input_ids'].shape, flush=True)
with torch.no_grad():
    out = model.generate(
        input_ids=inputs['input_ids'],
        pixel_values=inputs['pixel_values'],
        max_new_tokens=512,
        num_beams=1,
        do_sample=False,
    )
raw_tok = processor.tokenizer.decode(out[0], skip_special_tokens=True)
raw_batch = processor.batch_decode(out, skip_special_tokens=False)[0]
print("\n===== tokenizer.decode (skip_special=True) =====")
print(raw_tok)
print("\n===== batch_decode (skip_special=False) =====")
print(raw_batch)
