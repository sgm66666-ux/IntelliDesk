import { describe, expect, it } from 'vitest';
import chatPageSource from '@/views/ChatPage.vue?raw';

describe('ChatPage visual safety', () => {
  it('keeps the user-message icon from expanding inside the flex bubble', () => {
    expect(chatPageSource).toMatch(/&--user\s*>\s*svg\s*\{[^}]*width:\s*16px;/s);
    expect(chatPageSource).toMatch(/&--user\s*>\s*svg\s*\{[^}]*height:\s*16px;/s);
    expect(chatPageSource).toMatch(/&--user\s*>\s*svg\s*\{[^}]*flex:\s*0\s+0\s+16px;/s);
  });
});
