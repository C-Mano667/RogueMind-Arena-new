const fs = require('fs');

const base64Png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
const buffer = Buffer.from(base64Png, 'base64');
const dir = './app/src/main/res/drawable';

['image_2.png', 'soldier3.png', 'boss3.png', 'arena3.png'].forEach(file => {
    fs.writeFileSync(`${dir}/${file}`, buffer);
    console.log(`Created ${file}`);
});
