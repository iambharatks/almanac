class Solution {
public:
    int maxProduct(int n) {
        vector<int> count(10,0);
        while(n){
            count[n%10]++;
            n /= 10;
        }
        int res = 1, ops = 2;
        for(int i = 9 ; i >= 0 && ops > 0; i--){
            if(count[i]){
                if(ops == 1) return res*i;
                else if(count[i] >= ops) return i*i;
                res *= i;
                ops--;
                count[i]--;
            }
        }
        return res;
    }
};