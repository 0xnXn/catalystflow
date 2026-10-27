import puppeteer from 'puppeteer';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const url = process.env.FRONTEND_BASE_URL;
assert.ok(url?.startsWith('https://'), 'FRONTEND_BASE_URL must be configured');
await fs.mkdir('test-results', {recursive:true});
const browser = await puppeteer.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
try {
 for (const role of ['Admin','Manager','Employee']) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  await page.setViewport({width:1440,height:1000});
  try {
   await page.goto(url + '/login?ngsw-bypass=true', {waitUntil:'networkidle2',timeout:60000});
   const selector = `button[aria-label="Use ${role} demo credentials"]`;
   await page.waitForSelector(selector, {timeout:30000});
   await page.click(selector);
   await page.waitForFunction(expected => document.querySelector('#login-email')?.value === expected,
     {timeout:10000}, `demo.${role.toLowerCase()}@catalystflow.example.com`);
   await page.waitForFunction(() => document.querySelector('#login-password')?.value === 'CatalystDemo@123', {timeout:10000});
   assert.equal(await page.$eval('#login-email', el => el.value), `demo.${role.toLowerCase()}@catalystflow.example.com`);
   await page.click('.login-btn');
   await page.waitForFunction(() => location.pathname === '/dashboard', {timeout:60000});
   await page.waitForFunction(name => document.body.innerText.includes('Welcome back, Demo ' + name), {timeout:30000}, role);
   const text = await page.$eval('body', el => el.innerText);
   assert.ok(!text.includes('Incorrect email or password.'));
   await page.screenshot({path:`test-results/${role.toLowerCase()}.png`,fullPage:true});
   console.log(`PASS ${role}: live Cognito sign-in, profile and dashboard`);
  } catch(error) {
   await page.screenshot({path:`test-results/${role.toLowerCase()}-failure.png`,fullPage:true});
   throw error;
  } finally { await context.close(); }
 }
} finally { await browser.close(); }
