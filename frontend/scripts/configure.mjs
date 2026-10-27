import fs from 'node:fs';
const names=['API_URL','COGNITO_USER_POOL_ID','COGNITO_CLIENT_ID'];
for(const name of names) if(!process.env[name]) throw new Error(`${name} must be supplied for this account`);
if(!process.env.API_URL.startsWith('https://')) throw new Error('API_URL must use HTTPS');
const config={production:true,demoAccountsEnabled:process.env.DEMO_ACCOUNTS_ENABLED==='true',apiUrl:process.env.API_URL,cognito:{userPoolId:process.env.COGNITO_USER_POOL_ID,clientId:process.env.COGNITO_CLIENT_ID,region:process.env.AWS_REGION||'ap-southeast-2'}};
fs.writeFileSync('src/environment/environment.prod.ts','export const environment = '+JSON.stringify(config,null,2)+';\n');
