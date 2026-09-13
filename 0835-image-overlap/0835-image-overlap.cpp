class Solution {
public:
    int largestOverlap(vector<vector<int>>& img1, vector<vector<int>>& img2) {
        int n = size(img1);
        int res = 0;
        for(int rshift = -n+1 ; rshift < n ; rshift++){
            for(int cshift = -n+1 ; cshift < n ; cshift++){
                int cur = 0;
                for(int i1 = 0, i2 = rshift; i2 < n ; i2++,i1++){
                    for(int j1 = 0, j2 = cshift; j2 < n ; j2++,j1++){
                        if(i2 < 0 || j2 < 0 || i1 >= n || j1 >= n) continue;
                        cur += (img1[i2][j2]&img2[i1][j1]);
                    }
                }
                res = max(cur,res);
            }
        }
        return  res;
    }
};