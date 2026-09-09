UPDATE items SET slug = 'legacy-item-' || id WHERE slug IS NULL;
